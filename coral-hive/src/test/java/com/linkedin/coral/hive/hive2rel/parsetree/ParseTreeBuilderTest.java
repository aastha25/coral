// ... existing imports and class definition ...

@Test
public void testSumAggregation() {
  HiveFunctionResolver functionResolver = 
      new HiveFunctionResolver(new StaticHiveFunctionRegistry(), new ConcurrentHashMap<>());
  ParseTreeBuilder parseTreeBuilder = new ParseTreeBuilder(functionResolver);
  
  String sql = "SELECT SUM(int_required) FROM exhaustive_primitives";
  
  // Should parse successfully without throwing exceptions
  assertDoesNotThrow(() -> parseTreeBuilder.parse(sql, null));
  
  // Verify parsed result is not null
  ASTNode result = parseTreeBuilder.parse(sql, null);
  assertNotNull(result);
}
```

### Conclusion
- The test ensures the SQL statement parses correctly.
- If the `SUM` function is already supported, the test will confirm this.
- If not, make necessary changes to the grammar or registry as identified in Stage 1.

### Critical Notes
- **Focus:** Ensure parsing only; do not handle translation.
- **Test Coverage:** Even if no code changes are needed, a test must be added.
- **Minimal Changes:** Modify only what's necessary for parsing success.
- **Java Syntax:** Ensure correct Java and JUnit usage.
- **No Breaking Changes:** Maintain existing functionality integrity.