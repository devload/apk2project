#!/bin/bash
set -e

cd /Users/devload/whatap/apk2project

echo "Step 1: Removing old test output..."
rm -rf test_output/hanacard_project

echo "Step 2: Building project..."
./gradlew clean build

echo "Step 3: Running generator..."
./gradlew run --args="generate test_apks/hanacard.apk -o test_output/hanacard_project"

echo "Step 4: Compiling generated project..."
cd test_output/hanacard_project
./gradlew compileDebugJavaWithJavac 2>&1 | head -100
