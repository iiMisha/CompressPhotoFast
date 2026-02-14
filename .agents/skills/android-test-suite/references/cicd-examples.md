# CI/CD Integration

Примеры интеграции тестов в CI/CD пайплайны.

## GitHub Actions

```yaml
name: Android Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Run unit tests
        run: ./gradlew testDebugUnitTest
      - name: Generate coverage
        run: ./gradlew jacocoTestReport
      - name: Upload coverage
        uses: codecov/codecov-action@v3

  instrumentation-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Run instrumentation tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 29
          script: ./gradlew connectedDebugAndroidTest
```

## GitLab CI

```yaml
stages:
  - test

unit_tests:
  stage: test
  image: openjdk:17
  script:
    - ./gradlew testDebugUnitTest
  artifacts:
    reports:
      junit: app/build/test-results/**/*.xml

instrumentation_tests:
  stage: test
  image: androidsdk/android-29
  script:
    - ./scripts/run_instrumentation_tests.sh
```

## Jenkins

```groovy
pipeline {
    agent any
    
    stages {
        stage('Unit Tests') {
            steps {
                sh './gradlew testDebugUnitTest'
            }
            post {
                always {
                    junit '**/build/test-results/**/*.xml'
                }
            }
        }
    }
}
```
