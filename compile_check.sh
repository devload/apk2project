#!/bin/bash
cd /Users/devload/whatap/apk2project
./gradlew compileKotlin --console=plain 2>&1
echo "Exit code: $?"
