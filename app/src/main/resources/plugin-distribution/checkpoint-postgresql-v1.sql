-- Administrator-only provisioning. Execute once, as a dedicated non-client owner.
-- No production client runs this file. Grants/provisioning of individual slots are separate.
BEGIN;
CREATE SCHEMA plugin_checkpoints;
REVOKE ALL ON SCHEMA plugin_checkpoints FROM PUBLIC;

CREATE TABLE plugin_checkpoints.slots (
    installation_id text NOT NULL CHECK (installation_id ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    trust_domain text NOT NULL CHECK (trust_domain ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    root_hash text NOT NULL CHECK (root_hash ~ '^sha256:[0-9a-f]{64}$'),
    client_role name NOT NULL,
    installation_hash text NOT NULL DEFAULT '',
    checkpoint jsonb NOT NULL DEFAULT 'null'::jsonb,
    operation_count integer NOT NULL DEFAULT 0,
    maximum_operations integer NOT NULL DEFAULT 100000 CHECK (maximum_operations BETWEEN 1 AND 1000000),
    PRIMARY KEY (installation_id, trust_domain),
    CHECK ((installation_hash = '' AND checkpoint = 'null'::jsonb)
        OR (installation_hash ~ '^sha256:[0-9a-f]{64}$' AND jsonb_typeof(checkpoint) = 'object')),
    CHECK (operation_count BETWEEN 0 AND maximum_operations)
);
CREATE TABLE plugin_checkpoints.operations (
    installation_id text NOT NULL,
    trust_domain text NOT NULL,
    operation_id text NOT NULL CHECK (operation_id ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    request_json text NOT NULL CHECK (octet_length(request_json) <= 16384),
    outcome text NOT NULL CHECK (outcome IN ('COMMITTED','REJECTED')),
    PRIMARY KEY (installation_id, trust_domain, operation_id),
    FOREIGN KEY (installation_id, trust_domain) REFERENCES plugin_checkpoints.slots
);
REVOKE ALL ON ALL TABLES IN SCHEMA plugin_checkpoints FROM PUBLIC;

CREATE FUNCTION plugin_checkpoints.authorized_slot(p_id text, p_domain text, p_root text)
RETURNS plugin_checkpoints.slots
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE s plugin_checkpoints.slots;
BEGIN
    SELECT * INTO s FROM plugin_checkpoints.slots
      WHERE installation_id = p_id AND trust_domain = p_domain AND root_hash = p_root
        AND client_role = SESSION_USER;
    IF NOT FOUND THEN RAISE EXCEPTION 'checkpoint scope unavailable' USING ERRCODE='42501'; END IF;
    -- Misconfigured client roles cannot claim this authority boundary, including SET ROLE escalation.
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles r
        WHERE pg_catalog.pg_has_role(SESSION_USER, r.oid, 'MEMBER')
          AND (r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.rolbypassrls
            OR pg_catalog.has_schema_privilege(r.oid, 'plugin_checkpoints', 'CREATE')
            OR pg_catalog.has_table_privilege(r.oid, 'plugin_checkpoints.slots', 'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER')
            OR pg_catalog.has_table_privilege(r.oid, 'plugin_checkpoints.operations', 'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER')
            OR pg_catalog.has_any_column_privilege(r.oid, 'plugin_checkpoints.slots', 'INSERT,UPDATE')
            OR pg_catalog.has_any_column_privilege(r.oid, 'plugin_checkpoints.operations', 'INSERT,UPDATE'))) THEN
        RAISE EXCEPTION 'checkpoint client role has unsafe authority privileges' USING ERRCODE='42501';
    END IF;
    RETURN s;
END $$;

CREATE FUNCTION plugin_checkpoints.checked_checkpoint(p jsonb, p_domain text)
RETURNS bigint LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE sequence_value bigint; payload text;
BEGIN
    IF jsonb_typeof(p) IS DISTINCT FROM 'object'
        OR (p - ARRAY['schema','trustDomainId','sequence','revisionHash','contentHash']) <> '{}'::jsonb
        OR NOT (p ?& ARRAY['schema','trustDomainId','sequence','revisionHash','contentHash'])
        OR jsonb_typeof(p->'trustDomainId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(p->'revisionHash') IS DISTINCT FROM 'string'
        OR jsonb_typeof(p->'contentHash') IS DISTINCT FROM 'string'
        OR p->>'schema' IS DISTINCT FROM 'regelsuche.plugin-trust-store-chain-checkpoint/v1'
        OR p->>'trustDomainId' IS DISTINCT FROM p_domain
        OR jsonb_typeof(p->'sequence') IS DISTINCT FROM 'number'
        OR NOT (p->>'sequence' ~ '^[1-9][0-9]{0,18}$')
        OR NOT coalesce(p->>'revisionHash' ~ '^sha256:[0-9a-f]{64}$',false)
        OR NOT coalesce(p->>'contentHash' ~ '^sha256:[0-9a-f]{64}$',false) THEN
        RAISE EXCEPTION 'invalid checkpoint' USING ERRCODE='22023';
    END IF;
    sequence_value := (p->>'sequence')::bigint;
    -- Independent consumption of the unchanged Java ChainCheckpoint/v1 canonical algorithm.
    payload := '{"schema":"regelsuche.plugin-trust-store-chain-checkpoint/v1","trustDomainId":"'
      || p_domain || '","sequence":' || sequence_value || ',"revisionHash":"' || (p->>'revisionHash') || '"}' || chr(10);
    IF p->>'contentHash' IS DISTINCT FROM
        'sha256:' || encode(sha256(convert_to(payload, 'UTF8')), 'hex') THEN
        RAISE EXCEPTION 'checkpoint content hash mismatch' USING ERRCODE='22023';
    END IF;
    RETURN sequence_value;
END $$;

-- Private renderer for the bounded Operation/v1 wire shape, not a general JSON canonicalizer.
-- Its admitted values are ASCII identifiers/hashes, integral sequences, objects and null.
-- The checkpoint's content-hash algorithm above deliberately retains its own field order.
CREATE FUNCTION plugin_checkpoints.canonical_operation_json(p jsonb, p_depth integer DEFAULT 0)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT SET search_path = pg_catalog, pg_temp AS $$
DECLARE rendered text;
BEGIN
    IF p_depth < 0 OR p_depth > 3 THEN
        RAISE EXCEPTION 'operation JSON nesting limit' USING ERRCODE='22023';
    END IF;
    IF jsonb_typeof(p) = 'object' THEN
        SELECT '{' || coalesce(string_agg(to_jsonb(member.key)::text || ':'
            || plugin_checkpoints.canonical_operation_json(member.value,p_depth+1),
            ',' ORDER BY member.key COLLATE "C"),'') || '}' INTO rendered
            FROM jsonb_each(p) AS member;
        RETURN rendered;
    END IF;
    IF jsonb_typeof(p) IN ('string','number','null') THEN RETURN p::text; END IF;
    RAISE EXCEPTION 'unsupported operation JSON value' USING ERRCODE='22023';
END $$;

CREATE FUNCTION plugin_checkpoints.read_state(p_id text, p_domain text, p_root text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE s plugin_checkpoints.slots;
BEGIN
    s := plugin_checkpoints.authorized_slot(p_id,p_domain,p_root);
    RETURN jsonb_build_object('installationHash',s.installation_hash,'checkpoint',s.checkpoint)::text;
END $$;

CREATE FUNCTION plugin_checkpoints.lookup_operation(p_id text, p_domain text, p_root text, p_operation text)
RETURNS TABLE(request_json text, outcome text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM plugin_checkpoints.authorized_slot(p_id,p_domain,p_root);
    IF p_operation IS NULL OR NOT (p_operation ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$') THEN
        RAISE EXCEPTION 'invalid operation identifier' USING ERRCODE='22023';
    END IF;
    RETURN QUERY SELECT o.request_json,o.outcome FROM plugin_checkpoints.operations o
        WHERE o.installation_id=p_id AND o.trust_domain=p_domain AND o.operation_id=p_operation;
END $$;

CREATE FUNCTION plugin_checkpoints.submit_operation(p_request text)
RETURNS TABLE(request_json text, outcome text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE v jsonb; sc jsonb; e jsonb; u jsonb; s plugin_checkpoints.slots;
    prior plugin_checkpoints.operations; before_sequence bigint; after_sequence bigint; verdict text;
BEGIN
    IF p_request IS NULL OR octet_length(p_request) > 16384 THEN
        RAISE EXCEPTION 'operation byte limit' USING ERRCODE='22023';
    END IF;
    v := p_request::jsonb; sc := v->'scope'; e := v->'expected'; u := v->'update';
    IF jsonb_typeof(v) IS DISTINCT FROM 'object'
        OR (v - ARRAY['schema','scope','operationId','intentHash','expected','update']) <> '{}'::jsonb
        OR NOT (v ?& ARRAY['schema','scope','operationId','intentHash','expected','update'])
        OR v->>'schema' IS DISTINCT FROM 'regelsuche.plugin-checkpoint-operation/v1'
        OR jsonb_typeof(v->'operationId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(v->'intentHash') IS DISTINCT FROM 'string'
        OR NOT coalesce(v->>'operationId' ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$',false)
        OR NOT coalesce(v->>'intentHash' ~ '^sha256:[0-9a-f]{64}$',false)
        OR jsonb_typeof(sc) IS DISTINCT FROM 'object'
        OR (sc - ARRAY['installationId','trustDomain','rootTrustStoreHash']) <> '{}'::jsonb
        OR NOT (sc ?& ARRAY['installationId','trustDomain','rootTrustStoreHash'])
        OR jsonb_typeof(sc->'installationId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(sc->'trustDomain') IS DISTINCT FROM 'string'
        OR jsonb_typeof(sc->'rootTrustStoreHash') IS DISTINCT FROM 'string'
        OR jsonb_typeof(e->'installationHash') IS DISTINCT FROM 'string'
        OR jsonb_typeof(u->'installationHash') IS DISTINCT FROM 'string'
        OR jsonb_typeof(e) IS DISTINCT FROM 'object' OR jsonb_typeof(u) IS DISTINCT FROM 'object'
        OR (e - ARRAY['installationHash','checkpoint']) <> '{}'::jsonb
        OR (u - ARRAY['installationHash','checkpoint']) <> '{}'::jsonb
        OR NOT (e ?& ARRAY['installationHash','checkpoint']) OR NOT (u ?& ARRAY['installationHash','checkpoint'])
        OR NOT coalesce(u->>'installationHash' ~ '^sha256:[0-9a-f]{64}$',false)
        OR (u->>'installationHash') IS NOT DISTINCT FROM (e->>'installationHash') THEN
        RAISE EXCEPTION 'invalid complete operation' USING ERRCODE='22023';
    END IF;
    s := plugin_checkpoints.authorized_slot(sc->>'installationId',sc->>'trustDomain',sc->>'rootTrustStoreHash');
    -- Java Operation.read requires these exact UTF-8 bytes, not just JSONB equality.
    -- Check before locking, idempotency lookup or any durable state/receipt/capacity change.
    IF convert_to(p_request,'UTF8') IS DISTINCT FROM
        convert_to(plugin_checkpoints.canonical_operation_json(v) || chr(10),'UTF8') THEN
        RAISE EXCEPTION 'operation request must use canonical JSON bytes' USING ERRCODE='22023';
    END IF;
    -- Serialize the entire operation, including duplicate-ID lookup, on the provisioned slot.
    SELECT * INTO STRICT s FROM plugin_checkpoints.slots
        WHERE installation_id=s.installation_id AND trust_domain=s.trust_domain FOR UPDATE;
    SELECT * INTO prior FROM plugin_checkpoints.operations o
        WHERE o.installation_id=s.installation_id AND o.trust_domain=s.trust_domain
          AND o.operation_id=v->>'operationId';
    IF FOUND THEN
        IF prior.request_json IS DISTINCT FROM p_request THEN
            RAISE EXCEPTION 'checkpoint operation ID conflicts' USING ERRCODE='23505';
        END IF;
        RETURN QUERY SELECT prior.request_json,prior.outcome;
        RETURN;
    END IF;
    IF s.operation_count >= s.maximum_operations THEN
        RAISE EXCEPTION 'checkpoint receipt capacity exhausted' USING ERRCODE='54000';
    END IF;
    after_sequence := plugin_checkpoints.checked_checkpoint(u->'checkpoint',s.trust_domain);
    IF e->'checkpoint' = 'null'::jsonb AND e->>'installationHash' = '' THEN before_sequence := 0;
    ELSE
        IF NOT coalesce(e->>'installationHash' ~ '^sha256:[0-9a-f]{64}$',false) THEN
            RAISE EXCEPTION 'invalid expected generation' USING ERRCODE='22023';
        END IF;
        before_sequence := plugin_checkpoints.checked_checkpoint(e->'checkpoint',s.trust_domain);
    END IF;
    IF u->'checkpoint' IS DISTINCT FROM e->'checkpoint'
        AND (after_sequence::numeric <> before_sequence::numeric + 1) THEN
        RAISE EXCEPTION 'checkpoint must be unchanged or immediate successor' USING ERRCODE='22023';
    END IF;
    verdict := CASE WHEN s.installation_hash=e->>'installationHash' AND s.checkpoint=e->'checkpoint'
        THEN 'COMMITTED' ELSE 'REJECTED' END;
    PERFORM set_config('synchronous_commit','on',true);
    INSERT INTO plugin_checkpoints.operations VALUES(s.installation_id,s.trust_domain,v->>'operationId',p_request,verdict);
    UPDATE plugin_checkpoints.slots SET operation_count=operation_count+1,
        installation_hash=CASE WHEN verdict='COMMITTED' THEN u->>'installationHash' ELSE installation_hash END,
        checkpoint=CASE WHEN verdict='COMMITTED' THEN u->'checkpoint' ELSE checkpoint END
        WHERE installation_id=s.installation_id AND trust_domain=s.trust_domain;
    RETURN QUERY SELECT p_request,verdict;
END $$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA plugin_checkpoints FROM PUBLIC;
COMMIT;
