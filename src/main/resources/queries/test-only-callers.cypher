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
//   CREATE INDEX annotation_fqn IF NOT EXISTS
//     FOR (a:Annotation) ON (a.fqn);
//   CREATE CONSTRAINT method_sig IF NOT EXISTS
//     FOR (m:Method) REQUIRE m.signature IS UNIQUE;
// ============================================================

// Step 1 – walk every CALLS edge and tag each caller as test/non-test inline.
//          A caller can be the test method itself or a nested element such as a lambda.
MATCH (callee:Method)<-[:CALLS]-(caller:CodeElement)
WITH callee,
     caller,
     EXISTS {
         MATCH (testMethod:Method)-[:ENCLOSES*0..]->(caller)
         MATCH (testMethod)-[:ANNOTATED_BY]->(a:Annotation)
         WHERE a.fqn IN [
             'org.junit.Test',                             // JUnit 4
             'org.junit.jupiter.api.Test',                 // JUnit 5
             'org.junit.jupiter.params.ParameterizedTest', // JUnit 5 parameterized
             'Test',                                      // unresolved imports
             'ParameterizedTest'
         ]
     } AS callerIsTest

// Step 2 – aggregate per callee: total callers vs. test callers.
WITH callee,
     count(DISTINCT caller)                                   AS totalCallers,
     count(DISTINCT CASE WHEN callerIsTest THEN caller END)    AS testCallers

// Step 3 – keep only methods where every caller is a test method
//           and the callee itself is not a test method.
WHERE totalCallers > 0
  AND totalCallers = testCallers
  AND NOT EXISTS {
      MATCH (callee)-[:ANNOTATED_BY]->(a:Annotation)
      WHERE a.fqn IN [
          'org.junit.Test',
          'org.junit.jupiter.api.Test',
          'org.junit.jupiter.params.ParameterizedTest',
          'Test',
          'ParameterizedTest'
      ]
  }

RETURN
    callee.fqn       AS fqn,
    callee.signature AS signature,
    testCallers      AS testCallerCount
ORDER BY fqn;
