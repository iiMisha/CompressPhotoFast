#!/bin/bash

# Script to run performance tests for CompressPhotoFast
# Measures execution time, memory usage, and throughput

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
CATEGORY=""
CLEAN_BUILD=false
SKIP_DEVICE_CHECK=false
ITERATIONS=1
OUTPUT_DIR="app/build/performance-tests"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --category)
            CATEGORY="$2"
            shift 2
            ;;
        --clean)
            CLEAN_BUILD=true
            shift
            ;;
        --skip-device-check)
            SKIP_DEVICE_CHECK=true
            shift
            ;;
        --iterations)
            ITERATIONS="$2"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --category CAT          Run specific test category (compression, memory, throughput)"
            echo "  --clean                 Clean build before running tests"
            echo "  --skip-device-check     Skip device connection check"
            echo "  --iterations N          Run tests N times (default: 1)"
            echo "  --output-dir DIR        Output directory for results (default: app/build/performance-tests)"
            echo "  --help                  Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0 --category compression"
            echo "  $0 --clean --iterations 3"
            echo "  $0 --category memory --output-dir ./perf-results"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

echo -e "${BLUE}==================================${NC}"
echo -e "${BLUE}CompressPhotoFast Performance Tests${NC}"
echo -e "${BLUE}==================================${NC}"
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Check for connected device
if [ "$SKIP_DEVICE_CHECK" = false ]; then
    echo -e "${BLUE}Checking for connected devices...${NC}"
    DEVICE_COUNT=$(adb devices | grep -w "device" | wc -l)

    if [ "$DEVICE_COUNT" -eq 0 ]; then
        echo -e "${RED}No Android devices found!${NC}"
        echo -e "${YELLOW}Please connect a device or start an emulator:${NC}"
        echo "  adb devices"
        echo "  emulator -avd <avd_name>"
        exit 1
    fi

    echo -e "${GREEN}✓ Found $DEVICE_COUNT device(s)${NC}"
    adb devices
    echo ""
fi

# Clean build if requested
if [ "$CLEAN_BUILD" = true ]; then
    echo -e "${YELLOW}Cleaning build...${NC}"
    ./gradlew clean
    echo -e "${GREEN}✓ Build cleaned${NC}"
    echo ""
fi

# Build the project
echo -e "${BLUE}Building performance tests...${NC}"
./gradlew assembleAndroidTest assembleDebug

if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Build failed${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Build successful${NC}"
echo ""

# Determine which tests to run
TEST_CLASS=""

if [ -n "$CATEGORY" ]; then
    case $CATEGORY in
        compression)
            TEST_CLASS="com.compressphotofast.performance.CompressionPerformanceTest"
            ;;
        memory)
            TEST_CLASS="com.compressphotofast.performance.MemoryLeakTest"
            ;;
        throughput)
            TEST_CLASS="com.compressphotofast.performance.ThroughputTest"
            ;;
        *)
            echo -e "${RED}Unknown category: $CATEGORY${NC}"
            echo "Valid categories: compression, memory, throughput"
            exit 1
            ;;
    esac
else
    # Run all performance tests
    TEST_CLASS="com.compressphotofast.performance"
fi

echo -e "${BLUE}Running performance tests...${NC}"
echo -e "Category: ${CATEGORY:-all}"
echo -e "Iterations: $ITERATIONS"
echo ""

# Run tests for specified iterations
RESULTS_FILE="$OUTPUT_DIR/results_$TIMESTAMP.txt"

for ((i=1; i<=ITERATIONS; i++)); do
    echo -e "${YELLOW}Iteration $i/$ITERATIONS${NC}"

    # Run tests and capture output
    if [ -n "$TEST_CLASS" ]; then
        ./gradlew connectedDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
            2>&1 | tee -a "$RESULTS_FILE"
    else
        ./gradlew connectedDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.package="com.compressphotofast.performance" \
            2>&1 | tee -a "$RESULTS_FILE"
    fi

    # Wait between iterations
    if [ $i -lt $ITERATIONS ]; then
        echo -e "${BLUE}Waiting 5 seconds before next iteration...${NC}"
        sleep 5
    fi
done

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Performance tests completed!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# Extract and display summary
echo -e "${BLUE}Test Results Summary:${NC}"
echo "Results saved to: $RESULTS_FILE"
echo ""

# Parse test results
if [ -f "$RESULTS_FILE" ]; then
    # Extract test counts
    TOTAL_TESTS=$(grep -o "Tests [0-9]*" "$RESULTS_FILE" | tail -1 | grep -o "[0-9]*" || echo "0")
    FAILED_TESTS=$(grep -o "failed: [0-9]* failure" "$RESULTS_FILE" | grep -o "[0-9]*" || echo "0")

    echo -e "Total tests run: ${BLUE}$TOTAL_TESTS${NC}"
    echo -e "Failed tests: ${RED}$FAILED_TESTS${NC}"

    if [ "$FAILED_TESTS" -eq 0 ]; then
        echo -e "${GREEN}✓ All tests passed!${NC}"
    else
        echo -e "${RED}✗ Some tests failed${NC}"
    fi
fi

echo ""
echo -e "${BLUE}Performance metrics available in:${NC}"
echo "  HTML Report: app/build/reports/androidTests/connected/debug/index.html"
echo "  Raw Output: $RESULTS_FILE"
echo ""

# Extract benchmark results from device if available
echo -e "${BLUE}Fetching benchmark results from device...${NC}"
adb shell "run-as com.compressphotofast.debug cat files/benchmark_results.json" \
    > "$OUTPUT_DIR/benchmark_$TIMESTAMP.json" 2>/dev/null || true

if [ -f "$OUTPUT_DIR/benchmark_$TIMESTAMP.json" ]; then
    echo -e "${GREEN}✓ Benchmark results saved to: $OUTPUT_DIR/benchmark_$TIMESTAMP.json${NC}"
else
    echo -e "${YELLOW}⚠ No benchmark results found on device${NC}"
fi

echo ""
echo -e "${GREEN}Done!${NC}"
