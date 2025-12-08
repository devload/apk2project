#!/bin/bash
cd /Users/devload/whatap/apk2project
./gradlew compileKotlin --console=plain 2>&1 > /tmp/gradle_build_output.txt
echo "Build exit code: $?" >> /tmp/gradle_build_output.txt
