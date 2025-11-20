# Text Block Support for Multi-line Queries - Implementation Summary

## Problem
The original implementation was not properly converting multi-line SQL queries to Java text blocks. Queries with newline characters were appearing in the output files with escaped `\n` instead of being rendered as proper multi-line text blocks with `"""` delimiters.

## Root Cause
The issue was attempting to manually construct text block syntax as strings, rather than using JavaParser's built-in `TextBlockLiteralExpr` class which properly handles text block rendering.

## Solution

### Key Changes

1. **Added `TextBlockLiteralExpr` Import**
   - Imported JavaParser's `TextBlockLiteralExpr` class for proper text block handling

2. **Created `updateAnnotationValueWithTextBlockSupport` Method**
   - Detects if query contains newlines (either literal `\n` or actual newline characters)
   - Converts literal `\n` to actual newlines
   - Calls `updateAnnotationValue` with appropriate `useTextBlock` flag

3. **Enhanced `updateAnnotationValue` Method**
   - Added `useTextBlock` parameter
   - Creates either `TextBlockLiteralExpr` (for multi-line) or `StringLiteralExpr` (for single-line)
   - JavaParser automatically handles the rendering with proper `"""` delimiters

### Code Flow

```
Query String with \n
    ↓
updateAnnotationValueWithTextBlockSupport()
    ↓ (detects \n or newlines)
    ↓ (converts literal \n to actual newlines)
    ↓
updateAnnotationValue(method, name, query, useTextBlock=true)
    ↓
Creates TextBlockLiteralExpr(query)
    ↓
Sets as annotation value
    ↓
JavaParser renders as:
@Query("""
    SELECT *
    FROM users
    WHERE id = ?""")
```

## How It Works

### Detection
```java
boolean isMultiline = newStringValue != null && 
                     (newStringValue.contains("\\n") || newStringValue.contains("\n"));
```

### Conversion
```java
String processedValue = newStringValue.replace("\\n", "\n");
```

### Rendering
```java
if (useTextBlock) {
    newValueExpr = new TextBlockLiteralExpr(newStringValue);
} else {
    newValueExpr = new StringLiteralExpr(newStringValue);
}
```

## Output Examples

### Before (Incorrect)
```java
@Query("SELECT *\nFROM users\nWHERE id = ?")
```

### After (Correct)
```java
@Query("""
    SELECT *
    FROM users
    WHERE id = ?""")
```

## Testing

### Comprehensive Test Suite (QueryOptimizerTest.java)
A single comprehensive test suite containing 13 tests covering:

**Unit Tests (7 tests)**
- Single-line query handling with `StringLiteralExpr`
- Multi-line query handling with `TextBlockLiteralExpr`
- Literal backslash-n detection and conversion
- Normal annotation style handling (@Query(value = "..."))
- Preservation of other annotation properties
- Non-existent annotation handling
- Complex multi-line query validation

**Integration Tests (6 tests)**
- Text block rendering with triple quotes
- String literal rendering with escaped newlines
- Literal `\n` detection and conversion workflow
- Complete end-to-end workflow from string to text block
- AST expression type verification (TextBlockLiteralExpr)
- AST expression type verification (StringLiteralExpr)

### Test Results
✅ All 13 tests passing in single test suite
✅ Text blocks render correctly with `"""` delimiters
✅ Multi-line structure preserved
✅ No escaped `\n` in text block output
✅ Both unit and integration tests consolidated for easier maintenance

## Key Insights

1. **Don't Manually Format Text Blocks**: Let JavaParser's `TextBlockLiteralExpr` handle the rendering
2. **Detect Both Forms**: Check for both literal `\n` and actual newlines
3. **Convert Before Creating Expression**: Transform literal `\n` to actual newlines before passing to `TextBlockLiteralExpr`
4. **JavaParser Does the Heavy Lifting**: `TextBlockLiteralExpr.toString()` automatically produces proper Java 15+ text block syntax

## Files Modified

1. **QueryOptimizer.java**
   - Added `updateAnnotationValueWithTextBlockSupport()` method
   - Enhanced `updateAnnotationValue()` with text block support
   - Removed the old `convertToTextBlockIfMultiline()` approach

2. **QueryOptimizerTest.java**
   - Comprehensive test suite with 13 tests
   - 7 unit tests validating annotation value updates
   - 6 integration tests for end-to-end workflow validation
   - Tests both `StringLiteralExpr` and `TextBlockLiteralExpr` rendering
   - Verifies proper text block rendering in final output

## Benefits

- ✅ Clean, readable multi-line SQL queries in generated code
- ✅ Proper Java 15+ text block syntax
- ✅ No manual string manipulation
- ✅ Leverages JavaParser's built-in capabilities
- ✅ Comprehensive test coverage

