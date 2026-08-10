# Streaming JSON request bodies

Workbench JSON endpoints parse untrusted request bodies directly from the HTTP
`InputStream`. They do not construct a complete `byte[]` and then a second
complete UTF-8 `String` before parsing.

## Byte boundary

`BoundedRequestBody` wraps the transport in a forward-only byte-counting stream.
A valid `Content-Length` above the configured limit is rejected before the
request body is opened, but that header is only an optimization: the stream
counter remains authoritative for chunked requests, missing headers, malformed
headers and incorrect short declarations. The first byte above the boundary
raises the same typed `PAYLOAD_TOO_LARGE` HTTP 413 response on every documented
JSON route.

Skipping, mark/reset and alternate read methods cannot bypass the counter. The
wrapper owns and closes the underlying request body.

## Strict token decoding

`StreamingJsonRequestBody` uses Jackson Core's token API directly on the bounded
byte stream. Its per-factory constraints and parser features enforce:

- strict duplicate-key detection at every object depth;
- exactly one top-level JSON object and no trailing root value;
- rejection of malformed UTF-8 rather than replacement characters;
- bounded nesting, number length, property-name length, token count and document
  length;
- integral type checks for integer request fields;
- complete field consumption by every route decoder.

The byte-counting wrapper remains authoritative for HTTP 413. Parser structure
limits produce HTTP 400 and cannot accidentally weaken the configured body
limit. Error responses do not expose source snippets from the rejected body.

## Typed route boundary

The Workbench search, discovery, inventory import, AST-inspection, didactic,
proof and Rule Radar handlers decode fields directly into route-specific request
records. Unknown fields are skipped token by token and are not retained as a
generic object tree. Arrays and nested objects are materialized only when the
corresponding route consumes them. The Rule Radar context is decoded through a
nested typed cursor rather than through a root or nested `Map<String,Object>`.

A generic immutable-tree overload remains available for checkout-owned callers
that genuinely need a complete semantic JSON value. No documented Workbench
JSON POST route uses that overload.

This separation keeps Jackson at the untrusted HTTP boundary in the `app`
module. The core module's small `JsonReader` remains available for trusted,
checkout-owned artifact round-trips and does not acquire a web dependency.

## Claim boundary

This is blocking, bounded streaming on the JDK `HttpServer`. It reduces peak
request-body duplication and permits early rejection; it is not an HTTP/2,
reactive, non-blocking or back-pressure framework. Response bodies and static
resources have separate transport policies.

## Reproduction

Focused characterizations cover declared-length preflight, exact and
one-byte-over limits, small transport chunks, skip accounting, duplicate keys,
trailing documents, malformed UTF-8, wrong scalar types, incomplete decoders,
nesting limits, typed nested Rule Radar context decoding and the priority of
HTTP 413 over malformed oversized input. The full HTTP matrix additionally
exercises fixed-length and chunked requests against every OpenAPI JSON POST
operation.
