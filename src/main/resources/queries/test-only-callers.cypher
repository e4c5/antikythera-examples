// ============================================================
// Detect methods that are called ONLY by test methods.
// A "test method" is any Method node that carries an
// ANNOTATED_BY edge to a @Test or @ParameterizedTest annotation.
//
// Performance notes
// -----------------
// Collecting all test methods into a list and then using
// ALL(c IN list WHERE c IN list) is O(callers × tests).
// This version evaluates the "is-test?" flag per row with an
// EXISTS {} subquery (index-backed) and uses COUNT aggregation,
// removing the large in-memory list entirely.
//
// Recommended indexes (run once before executing this query):
//   CREATE INDEX annotation_sig IF NOT EXISTS
//     FOR (a:Annotation) ON (a.signature);
//   CREATE CONSTRAINT method_sig IF NOT EXISTS
//     FOR (m:Method) REQUIRE m.signature IS UNIQUE;
// ============================================================

// Step 1 – walk every CALLS edge and tag each caller as test/non-test inline.
MATCH (callee:Method)<-[:CALLS]-(caller:Method)
WITH callee,
     caller,
     EXISTS {
         MATCH (caller)-[:ANNOTATED_BY]->(a:Annotation)
         WHERE a.signature IN [
             'org.junit.Test',                             // JUnit 4
             'org.junit.jupiter.api.Test',                 // JUnit 5
             'org.junit.jupiter.params.ParameterizedTest'  // JUnit 5 parameterized
         ]
     } AS callerIsTest

// Step 2 – aggregate per callee: total callers vs. test callers.
WITH callee,
     count(caller)                             AS totalCallers,
     count(CASE WHEN callerIsTest THEN 1 END)  AS testCallers

// Step 3 – keep only methods where every caller is a test method
//           and the callee itself is not a test method.
WHERE totalCallers > 0
  AND totalCallers = testCallers
  AND NOT EXISTS {
      MATCH (callee)-[:ANNOTATED_BY]->(a:Annotation)
      WHERE a.signature IN [
          'org.junit.Test',
          'org.junit.jupiter.api.Test',
          'org.junit.jupiter.params.ParameterizedTest'
      ]
  }

RETURN
    callee.fqn       AS fqn,
    callee.signature AS signature,
    testCallers      AS testCallerCount
ORDER BY fqn;

