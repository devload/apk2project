import { NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';

// 기본 상태 (파이프라인 시작 전)
const defaultStatus = {
  phase: 'Waiting',
  currentPhase: 'IDLE',
  status: 'Waiting for pipeline to start...',
  isRunning: false,
  totalClasses: 0,
  totalMethods: 0,
  leafMethods: 0,
  processedMethods: 0,
  renamedMethods: 0,
  failedMethods: 0,
  currentIteration: 0,
  progress: 0,
  elapsedMs: 0,
  elapsedFormatted: '0:00',
  methodsPerMinute: 0,
  estimatedRemainingMs: 0,
  estimatedRemainingFormatted: '-',
  estimatedCompletionTime: '-',
  successRate: 0,
  parsedFiles: 0,
  totalFilesToParse: 0,
  failedParseFiles: 0,
  callGraphEdges: 0,
  phase1Progress: 0,
  callGraphClasses: 0,
  totalCallGraphClasses: 0,
  phase2Progress: 0,
  aiClientType: 'OLLAMA',
  aiClientAvailable: false,
  phase3Progress: 0,
  deepseekQueueSize: 0,
  qwenQueueSize: 0,
  renameQueueSize: 0,
  phase4Progress: 0,
  classDeepseekQueueSize: 0,
  classQwenQueueSize: 0,
  classRenameQueueSize: 0,
  batchSize: 10,
  recentRenames: [],
  recentLlmRequests: [],
  resourceHistory: [],
  cpuUsagePercent: 0,
  memoryUsedMb: 0,
  memoryTotalMb: 0,
  memoryUsagePercent: 0,
  lastUpdated: Date.now(),
};

export async function GET() {
  try {
    // status.json 파일 경로 - 여러 위치에서 찾기
    const possiblePaths = [
      path.join(process.cwd(), '..', 'hanacard_decompiled', 'sources', '.apk2project', 'status.json'),
      path.join(process.cwd(), '..', 'hana-decompiled', 'sources', '.apk2project', 'status.json'),
    ];

    const statusPath = possiblePaths.find(p => fs.existsSync(p));

    // 파일이 없으면 기본 상태 반환 (에러 대신)
    if (!statusPath) {
      return NextResponse.json(defaultStatus, {
        headers: {
          'Cache-Control': 'no-cache, no-store, must-revalidate',
          'Pragma': 'no-cache',
          'Expires': '0',
        },
      });
    }

    // 파일 읽기
    const data = fs.readFileSync(statusPath, 'utf-8');
    const status = JSON.parse(data);

    // CORS 헤더 추가
    return NextResponse.json(status, {
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'Pragma': 'no-cache',
        'Expires': '0',
      },
    });
  } catch (error) {
    console.error('Error reading status.json:', error);
    // 에러 시에도 기본 상태 반환
    return NextResponse.json({
      ...defaultStatus,
      status: 'Error reading status file',
    }, {
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
      },
    });
  }
}
