#!/bin/bash
# APK2Project 파이프라인 + Dashboard 종료 스크립트

set -e

PROJECT_DIR="/Users/devload/apk2proejct_ai/apk2project"
LOG_DIR="$PROJECT_DIR/logs"
PIPELINE_PID_FILE="$LOG_DIR/pipeline.pid"
DASHBOARD_PID_FILE="$LOG_DIR/dashboard.pid"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}APK2Project 프로세스 종료 중...${NC}"
echo ""

# 파이프라인 종료
if [ -f "$PIPELINE_PID_FILE" ]; then
    PIPELINE_PID=$(cat "$PIPELINE_PID_FILE")
    if ps -p $PIPELINE_PID > /dev/null 2>&1; then
        echo -e "${YELLOW}파이프라인 종료 (PID: $PIPELINE_PID)...${NC}"
        kill $PIPELINE_PID 2>/dev/null || true
        sleep 2
        # 강제 종료 시도
        if ps -p $PIPELINE_PID > /dev/null 2>&1; then
            echo -e "${RED}파이프라인 강제 종료...${NC}"
            kill -9 $PIPELINE_PID 2>/dev/null || true
        fi
        echo -e "${GREEN}✓ 파이프라인 종료 완료${NC}"
    else
        echo -e "${YELLOW}파이프라인 프로세스가 이미 종료됨${NC}"
    fi
    rm -f "$PIPELINE_PID_FILE"
else
    echo -e "${YELLOW}파이프라인 PID 파일 없음${NC}"
fi

# Dashboard 종료
if [ -f "$DASHBOARD_PID_FILE" ]; then
    DASHBOARD_PID=$(cat "$DASHBOARD_PID_FILE")
    if ps -p $DASHBOARD_PID > /dev/null 2>&1; then
        echo -e "${YELLOW}Dashboard 종료 (PID: $DASHBOARD_PID)...${NC}"
        kill $DASHBOARD_PID 2>/dev/null || true
        sleep 2
        # 강제 종료 시도
        if ps -p $DASHBOARD_PID > /dev/null 2>&1; then
            echo -e "${RED}Dashboard 강제 종료...${NC}"
            kill -9 $DASHBOARD_PID 2>/dev/null || true
        fi
        echo -e "${GREEN}✓ Dashboard 종료 완료${NC}"
    else
        echo -e "${YELLOW}Dashboard 프로세스가 이미 종료됨${NC}"
    fi
    rm -f "$DASHBOARD_PID_FILE"
else
    echo -e "${YELLOW}Dashboard PID 파일 없음${NC}"
fi

# 잔여 프로세스 확인 및 종료 (Gradle daemon)
echo ""
echo -e "${YELLOW}잔여 Gradle daemon 확인 중...${NC}"
GRADLE_PIDS=$(pgrep -f "gradle.*apk2project" || true)
if [ -n "$GRADLE_PIDS" ]; then
    echo -e "${YELLOW}Gradle daemon 종료 중...${NC}"
    echo "$GRADLE_PIDS" | xargs kill 2>/dev/null || true
    echo -e "${GREEN}✓ Gradle daemon 종료 완료${NC}"
else
    echo -e "${GREEN}잔여 Gradle daemon 없음${NC}"
fi

# Node.js 프로세스 확인
echo -e "${YELLOW}잔여 Node.js 프로세스 확인 중...${NC}"
NODE_PIDS=$(lsof -ti:$DASHBOARD_PORT 2>/dev/null || true)
if [ -n "$NODE_PIDS" ]; then
    echo -e "${YELLOW}Node.js 프로세스 종료 중...${NC}"
    echo "$NODE_PIDS" | xargs kill 2>/dev/null || true
    echo -e "${GREEN}✓ Node.js 프로세스 종료 완료${NC}"
else
    echo -e "${GREEN}잔여 Node.js 프로세스 없음${NC}"
fi

echo ""
echo -e "${GREEN}======================================${NC}"
echo -e "${GREEN}모든 프로세스 종료 완료${NC}"
echo -e "${GREEN}======================================${NC}"
