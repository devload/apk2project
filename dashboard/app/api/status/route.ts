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
  qwenQueueSize: null,
  renameQueueSize: 0,
  phase4Progress: 0,
  classDeepseekQueueSize: 0,
  classQwenQueueSize: null,
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

/**
 * Recursively search for all .apk2project/status.json files
 * @param startDir Starting directory for search
 * @param maxDepth Maximum recursion depth (default: 5 levels up)
 * @returns Array of status.json file paths
 */
function findStatusFiles(startDir: string, maxDepth: number = 5): string[] {
  const statusFiles: string[] = [];
  const visited = new Set<string>();
  const skipDirs = new Set([
    'node_modules', '.git', 'dist', 'build', 'target', 'out',
    '.next', '.idea', 'vscode', 'gradle', '.gradle'
  ]);

  function searchDir(dir: string, depth: number) {
    // Skip if already visited or too deep
    if (depth > maxDepth || visited.has(dir)) return;
    visited.add(dir);

    try {
      const entries = fs.readdirSync(dir, { withFileTypes: true });

      for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);

        if (entry.isDirectory()) {
          // Skip common non-project directories
          if (skipDirs.has(entry.name)) continue;
          searchDir(fullPath, depth + 1);
        } else if (entry.name === 'status.json') {
          // Only include if in .apk2project directory
          if (dir.endsWith('.apk2project')) {
            statusFiles.push(fullPath);
          }
        }
      }
    } catch (error) {
      // Ignore permission errors and non-existent directories
    }
  }

  // Search current directory and parent directories
  let currentDir = startDir;
  for (let i = 0; i < maxDepth; i++) {
    searchDir(currentDir, 0);

    const parent = path.dirname(currentDir);
    if (parent === currentDir) break; // Reached root
    currentDir = parent;
  }

  return statusFiles;
}

export async function GET() {
  try {
    // Dynamically search for all status.json files (no hardcoded paths)
    const statusFiles = findStatusFiles(process.cwd());

    // Select the most recently modified file
    const existingPaths = statusFiles
      .map(p => ({ path: p, mtime: fs.statSync(p).mtime.getTime() }))
      .sort((a, b) => b.mtime - a.mtime);

    const statusPath = existingPaths.length > 0 ? existingPaths[0].path : null;

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
    console.error('Error details:', JSON.stringify(error, null, 2));
    // 에러 시에도 기본 상태 반환
    return NextResponse.json({
      ...defaultStatus,
      status: `Error: ${error}`,
    }, {
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
      },
    });
  }
}
