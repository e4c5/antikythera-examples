How does method signature work?
--
```
┌─────────────────────────────────────────────────────────────────┐
│ QueryOptimizer.actOnAnalysisResult()                            │
│ - Analyzes repository methods                                   │
│ - Finds optimization opportunities                              │
│ - Creates list of updates (method signature changes)            │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ QueryOptimizer.updateMethodCallSignatures(updates, repoFqn)     │
│ ✓ Fixed: Correct method name matching (Bug #1)                  │
│ ✓ Fixed: LPP setup once per CU (Bug #3)                         │
│ ✓ Enhanced: Debug logging                                        │
└────────────────────────┬────────────────────────────────────────┘
                         │
                    ┌────┴────┐
                    │          │
                    ▼          ▼
          For each dependent class:
             NameChangeVisitor
             
    ┌──────────────────────────────────────┐
    │ Visit CompilationUnit                │
    │ Find all MethodCallExpr              │
    │ Check if scope matches field name    │
    │ Match method call to update          │
    │ ✓ Fixed: Correct method name match   │
    │   (Bug #1 - test mock fixed)         │
    └──────────────────────────────────────┘
                    │
                    ▼
          For each matching method call:
          
    ┌──────────────────────────────────────┐
    │ Update method name (if changed)      │
    │ Reorder arguments (if needed)        │
    │ ✓ Fixed: Break after processing      │
    │   (Bug #2)                           │
    │ Mark CU as modified                  │
    └──────────────────────────────────────┘
                    │
                    ▼
         Write modified CU to disk
         (Using LexicalPreservingPrinter
          which was properly setup once)
```
