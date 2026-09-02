from pathlib import Path

PROJECTOR = Path(
    "regelsuche-core/src/main/java/de/regelsuche/parse/"
    "ExactParsedSubtermProjector.java"
)
NESTED = Path(
    "app/src/main/java/de/regelsuche/polynomial/"
    "ExactNestedFactorizationTransformationPipeline.java"
)
TEST = Path(
    "regelsuche-core/src/test/java/de/regelsuche/parse/"
    "ExactParsedSubtermProjectorTest.java"
)
DOC = Path("docs/exact-parsed-subterm-projection.md")

projector = PROJECTOR.read_text(encoding="utf-8")
anchor = '''    public Policy policy() {
        return policy;
    }

'''
method = '''    /**
     * Reuses the parser-issued root companion without rebuilding shifted
     * ranges or literal evidence.
     *
     * <p>An empty path selects the exact object already held by the caller, so
     * the ordinary subtree scan and range-shift commitment would duplicate
     * evidence. The source hash is also the range commitment because no
     * coordinate system changes. Inputs with outer whitespace fall back to the
     * ordinary projector so the projected source still excludes that layout.</p>
     */
    public Result projectRootIdentity(
        ExactParsedTerm root,
        String expectedFormattedText
    ) {
        Objects.requireNonNull(root, "root");
        String expected = Objects.requireNonNull(
            expectedFormattedText,
            "expectedFormattedText");
        if (expected.isBlank()) {
            throw new IllegalArgumentException(
                "expected subtree text must not be blank");
        }
        ExactParsedTerm.SourceRange selectedRange = root.rootSourceRange();
        if (selectedRange.startInclusive() != 0
                || selectedRange.endExclusive() != root.source().length()) {
            return project(root, List.of(), expected);
        }

        Work work = new Work(policy.maxWorkUnits());
        if (root.source().length() > policy.maxRootSourceCodeUnits()) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                "MAX_ROOT_SOURCE_CODE_UNITS_EXCEEDED",
                List.of(),
                expected,
                "",
                Optional.empty(),
                Optional.empty(),
                "",
                "",
                work);
        }
        if (expected.length() > policy.maxFormattedCodeUnits()) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                "MAX_EXPECTED_FORMATTED_CODE_UNITS_EXCEEDED",
                List.of(),
                expected,
                "",
                Optional.empty(),
                Optional.empty(),
                "",
                "",
                work);
        }

        String rootSourceHash = "";
        try {
            work.consume(
                "projection.root-source-hash-code-units",
                Math.multiplyExact(4L, root.source().length()));
            rootSourceHash = sha256(root.source());
            int nodeCount = countIdentityNodes(root.expression());
            work.consume(
                "projection.staleness-format-node-visits",
                nodeCount);
            String actual = ExpressionFormatter.format(root.expression());
            work.consume(
                "projection.staleness-format-code-units",
                actual.length());
            work.consume(
                "projection.staleness-text-comparison",
                Math.addExact(
                    (long) expected.length(),
                    actual.length()));
            if (!expected.equals(actual)) {
                return failure(
                    Status.POSITION_STALE,
                    "SELECTED_POSITION_TEXT_IS_STALE",
                    List.of(),
                    expected,
                    rootSourceHash,
                    Optional.of(actual),
                    Optional.of(selectedRange),
                    rootSourceHash,
                    "",
                    work);
            }
            return new Result(
                Status.PROJECTED,
                "EXACT_ROOT_IDENTITY_REUSED",
                policy,
                List.of(),
                expected,
                Optional.of(actual),
                Optional.of(rootSourceHash),
                Optional.of(selectedRange),
                Optional.of(rootSourceHash),
                Optional.of(root),
                work.ledger());
        } catch (ProjectionLimitReached exception) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                List.of(),
                expected,
                rootSourceHash,
                Optional.empty(),
                Optional.of(selectedRange),
                rootSourceHash,
                "",
                work);
        } catch (ProjectionInvariantFailure exception) {
            return failure(
                Status.TECHNICAL_FAILURE,
                exception.getMessage(),
                List.of(),
                expected,
                rootSourceHash,
                Optional.empty(),
                Optional.of(selectedRange),
                rootSourceHash,
                "",
                work);
        }
    }

    private int countIdentityNodes(Expr root) {
        Deque<Expr> pending = new ArrayDeque<>();
        IdentityHashMap<Expr, Boolean> visited = new IdentityHashMap<>();
        pending.push(root);
        int count = 0;
        while (!pending.isEmpty()) {
            Expr node = pending.pop();
            if (visited.put(node, Boolean.TRUE) != null) {
                throw invariant("PROJECTED_ROOT_REUSES_NODE_IDENTITY");
            }
            count++;
            if (count > policy.maxSubtreeNodes()) {
                throw limit("MAX_SUBTREE_NODES_EXCEEDED");
            }
            if (node instanceof BinaryExpr binary) {
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (node instanceof FunctionExpr function) {
                List<Expr> arguments = function.arguments();
                for (int index = arguments.size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(arguments.get(index));
                }
            }
        }
        return count;
    }

'''
if method not in projector:
    if anchor not in projector:
        raise RuntimeError("projector policy anchor not found")
    projector = projector.replace(anchor, anchor + method, 1)
PROJECTOR.write_text(projector, encoding="utf-8")

nested = NESTED.read_text(encoding="utf-8")
old = '''        ExactParsedSubtermProjector.Result projection = projector.project(
            root,
            position.path(),
            position.text());
'''
new = '''        ExactParsedSubtermProjector.Result projection =
            position.path().isEmpty()
                ? projector.projectRootIdentity(root, position.text())
                : projector.project(
                    root,
                    position.path(),
                    position.text());
'''
if new not in nested:
    if old not in nested:
        raise RuntimeError("nested projection invocation not found")
    nested = nested.replace(old, new, 1)
NESTED.write_text(nested, encoding="utf-8")

test = TEST.read_text(encoding="utf-8")
addition = '''
    @Test
    void reusesTheExactRootWithoutShiftedRangeReconstruction() {
        ExactParsedTerm root = parser.parseExactTerm("x^2 - 1");

        var first = projector.projectRootIdentity(root, "x ^ 2 - 1");
        var repeated = projector.projectRootIdentity(root, "x ^ 2 - 1");

        assertTrue(first.successful(), first.detailCode());
        assertEquals("EXACT_ROOT_IDENTITY_REUSED", first.detailCode());
        assertSame(root, first.projected().orElseThrow());
        assertEquals(
            first.rootSourceHash().orElseThrow(),
            first.rangeCommitmentHash().orElseThrow());
        assertEquals(
            0L,
            first.work().units("projection.range-commitment-code-units"));
        assertEquals(
            0L,
            first.work().units("projection.shifted-range-bindings"));
        assertEquals(first.certificateHash(), repeated.certificateHash());
    }

    @Test
    void rootIdentityRetainsTheStalenessGuard() {
        ExactParsedTerm root = parser.parseExactTerm("x^2 - 1");

        var result = projector.projectRootIdentity(root, "x ^ 2 + 1");

        assertEquals(
            ExactParsedSubtermProjector.Status.POSITION_STALE,
            result.status());
        assertTrue(result.projected().isEmpty());
        assertEquals(
            "x ^ 2 - 1",
            result.actualFormattedText().orElseThrow());
    }

    @Test
    void rootIdentityFallsBackWhenOuterWhitespaceRequiresRangeShift() {
        ExactParsedTerm root = parser.parseExactTerm("  x + 1  ");

        var result = projector.projectRootIdentity(root, "x + 1");

        assertTrue(result.successful(), result.detailCode());
        assertEquals("EXACT_SUBTERM_PROJECTED", result.detailCode());
        assertEquals("x + 1", result.projected().orElseThrow().source());
    }
'''
if addition.strip() not in test:
    closing = test.rfind("}\n")
    if closing < 0:
        raise RuntimeError("projector test closing brace not found")
    test = test[:closing] + addition + test[closing:]
TEST.write_text(test, encoding="utf-8")

doc = DOC.read_text(encoding="utf-8")
paragraph = '''
### Root identity fast path

For an empty occurrence path whose parser source range already spans the whole
input, the projector reuses the original `ExactParsedTerm`. No child range or
literal coordinate changes, so rebuilding and hashing a shifted range map would
be duplicate work. The path still checks the formatter snapshot and binds the
root source hash; inputs with outer whitespace use the ordinary projection
path. Nested occurrences retain the complete scan and shifted-range evidence.
'''
if paragraph.strip() not in doc:
    doc += paragraph
DOC.write_text(doc, encoding="utf-8")
