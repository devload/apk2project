import { NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

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
 * output.properties에서 설정 읽기
 */
function getConfig() {
  try {
    // Dashboard runs from dashboard/ directory, so parent is project root
    const projectRoot = path.dirname(process.cwd());
    const propertiesPath = path.join(projectRoot, 'output.properties');

    console.log('[DEBUG] process.cwd():', process.cwd());
    console.log('[DEBUG] projectRoot:', projectRoot);
    console.log('[DEBUG] propertiesPath:', propertiesPath);
    console.log('[DEBUG] properties exists:', fs.existsSync(propertiesPath));

    if (!fs.existsSync(propertiesPath)) {
      return {
        outputDir: path.join(projectRoot, 'generate_project'),
        dashboardPort: 3000,
        ollamaBaseUrl: 'http://localhost:11434'
      };
    }

    const content = fs.readFileSync(propertiesPath, 'utf-8');
    const outputDirMatch = content.match(/output\.dir=(.+)/);
    const dashboardPortMatch = content.match(/dashboard\.port=(.+)/);
    const ollamaUrlMatch = content.match(/ollama\.baseUrl=(.+)/);

    let outputDir = outputDirMatch?.[1]?.trim() || 'generate_project';
    const dashboardPort = dashboardPortMatch?.[1]?.trim() || '3000';
    const ollamaBaseUrl = ollamaUrlMatch?.[1]?.trim() || 'http://localhost:11434';

    // 상대 경로면 프로젝트 루트 기준으로 resolve
    if (!path.isAbsolute(outputDir)) {
      outputDir = path.join(projectRoot, outputDir);
    }

    return { outputDir, dashboardPort: parseInt(dashboardPort), ollamaBaseUrl };
  } catch (error) {
    console.error('Error reading output.properties:', error);
    return {
      outputDir: path.join(path.dirname(process.cwd()), 'generate_project'),
      dashboardPort: 3000,
      ollamaBaseUrl: 'http://localhost:11434'
    };
  }
}

export async function GET() {
  try {
    // properties에서 설정 읽기
    const { outputDir } = getConfig();
    const statusPath = path.join(outputDir, '.apk2project', 'status.json');

    // 파일이 없으면 기본 상태 반환
    if (!fs.existsSync(statusPath)) {
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
      status: `Error: ${error}`,
    }, {
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
      },
    });
  }
}
