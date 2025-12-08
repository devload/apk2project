#!/bin/bash
# SDK Stub Generator Test Script
# Usage: ./test_sdk_stub.sh

set -e

echo "=========================================="
echo "SDK Stub Generator Test"
echo "=========================================="

cd /Users/devload/whatap/apk2project

echo ""
echo "[Step 1] Cleaning and building project..."
./gradlew clean build --console=plain

echo ""
echo "[Step 2] Generating hanacard_project with SDK stubs..."
rm -rf hanacard_project
./gradlew run --args="generate /Users/devload/whatap/apkInjector/android_apkinjector/clean_original.apk --output hanacard_project"

echo ""
echo "[Step 3] Compiling generated project..."
cd hanacard_project

# Count errors before fix
echo ""
echo "[Step 4] Counting compilation errors..."
ERROR_COUNT=$(./gradlew compileDebugJavaWithJavac 2>&1 | grep -c "error:" || true)
echo "Total errors: $ERROR_COUNT"

echo ""
echo "=========================================="
echo "Test complete!"
echo "=========================================="
