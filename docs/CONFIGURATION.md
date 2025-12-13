# Configuration Reference

Complete guide to configuring the Duplication Detector.

---

## Configuration File

Location: `src/main/resources/generator.yml`

### Basic Structure

```yaml
variables:
  projects_folder: ${HOME}/projects

base_path: /absolute/path/to/your/project

duplication_detector:
  target_class: "com.example.YourClass"
  min_lines: 5
  threshold: 0.75
```

---

## Core Settings

### `base_path` (required)
Absolute path to the project root containing `src/main/java`.

```yaml
base_path: /home/user/my-project
```

Or use variables:
```yaml
variables:
  projects_folder: ${HOME}/projects

base_path: ${projects_folder}/my-app
```

### `target_class` (required)
Fully qualified name of the class to analyze.

```yaml
duplication_detector:
  target_class: "com.example.service.UserService"
```

### `min_lines` (optional)
Minimum number of lines to consider as a duplicate.

- **Default**: 5
- **Range**: 3-20
- **Recommendation**: Start with 5, lower to 3 for more duplicates

```yaml
duplication_detector:
  min_lines: 5
```

### `threshold` (optional)
Similarity threshold as a decimal (0.0 to 1.0).

- **Default**: 0.75 (75% similar)
- **Range**: 0.60-0.95
- **Presets**:
  - `0.90`: Strict (only very similar code)
  - `0.75`: Balanced (recommended)
  - `0.60`: Lenient (catches more duplicates)

```yaml
duplication_detector:
  threshold: 0.75
```

---

## Presets

Use predefined configuration presets via CLI:

```bash
# Strict mode (90% threshold, 5 lines)
--strict

# Lenient mode (60% threshold, 3 lines)
--lenient
```

---

## CLI Overrides

Override YAML settings from command line:

```bash
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="analyze --threshold 80 --min-lines 3"
```

**Priority**: CLI args > generator.yml > defaults

---

## Advanced Configuration

### AI-Powered Method Naming

Configure the AI service for intelligent method name generation:

```yaml
ai_service:
  provider: "gemini"
  model: "gemini-2.0-flash-exp"
  api_endpoint: "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
  api_key: "${GEMINI_API_KEY}"
  timeout_seconds: 30
  max_retries: 2
```

**Environment Variable**:
```bash
export GEMINI_API_KEY="your-api-key-here"
```

### Similarity Weights (Advanced)

Fine-tune how similarity is calculated:

```yaml
duplication_detector:
  similarity_weights:
    lcs_weight: 0.4
    levenshtein_weight: 0.3
    structural_weight: 0.3
```

- **LCS** (Longest Common Subsequence): Token-level similarity
- **Levenshtein**: Edit distance
- **Structural**: AST structure matching

**Recommendation**: Use defaults unless you have specific needs

---

## Performance Tuning

### For Large Projects (10K+ lines)

**Increase threshold to reduce false positives:**
```yaml
duplication_detector:
  threshold: 0.85
  min_lines: 6
```

**Use strict preset:**
```bash
analyze --strict
```

### For Very Large Codebases (100K+ lines)

**Disable structural filter for speed:**
```yaml
duplication_detector:
  use_structural_filter: false
```

**Analyze per-package instead of entire project:**
```bash
# Analyze specific packages
analyze --target-package "com.example.service"
```

### Memory Optimization

**For projects with 50K+ sequences, increase JVM heap:**
```bash
export MAVEN_OPTS="-Xmx4g"
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="analyze"
```

### Performance Benchmarks

| Project Size | Sequences | Time (default) | Time (optimized) |
|--------------|-----------|----------------|------------------|
| Small (2K LOC) | ~500 | 10 seconds | 5 seconds |
| Medium (10K LOC) | ~2,500 | 2 minutes | 1 minute |
| Large (50K LOC) | ~12,000 | 15 minutes | 8 minutes |
| Very Large (100K+ LOC) | 25,000+ | 45+ minutes | 20 minutes |

**Optimization tips:**
- Pre-filtering reduces comparisons by 94-100%
- Parallel processing scales with CPU cores
- Use strict thresholds to focus on high-confidence duplicates

---

## Refactoring Modes

### Interactive Mode (Default)
Review each refactoring before applying.

```bash
refactor --mode interactive
```

**Best for**: Manual review, learning the tool

### Dry-Run Mode
Preview changes without modifying files.

```bash
refactor --mode dry-run
```

**Best for**: Testing, generating reports

### Batch Mode
Auto-apply high-confidence refactorings (>= 90% similarity).

```bash
refactor --mode batch
```

**Best for**: Automation, CI/CD pipelines

---

## Verification Levels

Control post-refactoring verification:

### Compile (Default)
Verify code compiles after refactoring.

```bash
refactor --verify compile
```

### Test
Run full test suite after refactoring.

```bash
refactor --verify test
```

**Warning**: Can be slow for large test suites.

### None
Skip verification (not recommended).

```bash
refactor --verify none
```

---

## Output Formats

### Text (Default)
Human-readable console output.

```bash
analyze
```

### JSON
Machine-readable output for integration.

```bash
analyze --json
```

**Output**:
```json
{
  "duplicates": [...],
  "clusters": [...],
  "metrics": {...}
}
```

---

## Examples

### Strict Analysis
Find only high-confidence duplicates:

```yaml
duplication_detector:
  threshold: 0.90
  min_lines: 7
```

### Lenient Analysis
Catch more potential duplicates:

```yaml
duplication_detector:
  threshold: 0.65
  min_lines: 3
```

### Production Pipeline
Safe batch refactoring:

```yaml
duplication_detector:
  threshold: 0.85
  min_lines: 5
```

```bash
refactor --mode batch --verify test
```

---

## CI/CD Integration

### GitHub Actions

Add to `.github/workflows/code-quality.yml`:

```yaml
name: Code Quality

on: [push, pull_request]

jobs:
  check-duplicates:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Analyze duplicates
        run: |
          mvn clean compile
          mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
            -Dexec.args="analyze --json --threshold 80" > duplicates.json
      
      - name: Upload results
        uses: actions/upload-artifact@v3
        with:
          name: duplication-report
          path: duplicates.json
```

### Jenkins Pipeline

Add to `Jenkinsfile`:

```groovy
pipeline {
    agent any
    
    stages {
        stage('Build') {
            steps {
                sh 'mvn clean compile'
            }
        }
        
        stage('Check Duplicates') {
            steps {
                sh '''
                    mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
                      -Dexec.args="analyze --json" > duplicates.json
                '''
                archiveArtifacts artifacts: 'duplicates.json'
            }
        }
    }
}
```

### GitLab CI

Add to `.gitlab-ci.yml`:

```yaml
check_duplicates:
  stage: test
  script:
    - mvn clean compile
    - mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI"
        -Dexec.args="analyze --json" > duplicates.json
  artifacts:
    reports:
      codequality: duplicates.json
    when: always
```

### Gradle Integration

Add to `build.gradle`:

```groovy
task checkDuplicates(type: JavaExec) {
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.raditha.dedup.cli.DuplicationDetectorCLI'
    args = ['analyze', '--threshold', '75']
}

check.dependsOn checkDuplicates
```

---

## Best Practices

1. **Start Conservative**: Use default threshold (0.75) initially
2. **Review First**: Always run `--mode dry-run` before actual refactoring
3. **Version Control**: Commit before refactoring for easy rollback
4. **Incremental**: Refactor one class at a time
5. **Test**: Use `--verify test` for critical code

---

## Troubleshooting

### Configuration not loading
- Check YAML syntax (indentation matters!)
- Ensure file is at `src/main/resources/generator.yml`

### Variables not resolving
- Use `${VAR_NAME}` syntax
- Check environment variables are set

### AI naming not working
- Verify `GEMINI_API_KEY` is set
- Check internet connection
- Falls back to semantic naming if AI fails

---

## See Also

- [QUICK_START.md](QUICK_START.md) - Quick start guide
- [Design Documents](duplication-detector/) - Technical architecture and design
