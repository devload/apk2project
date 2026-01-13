'use client';

import React, { useMemo, useState } from 'react';
import {
  ReactFlow,
  Node,
  Edge,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
  MarkerType,
  Position,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

interface LlmRequestEntry {
  methodName: string;
  requestType: string;
  model: string;
  promptPreview: string;
  response: string;
  durationMs: number;
  success: boolean;
  timestamp: number;
}

interface WorkflowGraphProps {
  currentPhase?: string;
  processedMethods?: number;
  renamedMethods?: number;
  leafMethods?: number;
  batchSize?: number;
  recentLlmRequests?: LlmRequestEntry[];
  parsedFiles?: number;
  totalClasses?: number;
  callGraphEdges?: number;
  failedMethods?: number;
  totalFilesToParse?: number;
  phase1Progress?: number;
  phase2Progress?: number;
  phase3Progress?: number;
  callGraphClasses?: number;
  totalCallGraphClasses?: number;
  deepseekQueueSize?: number;
  qwenQueueSize?: number | null;
  renameQueueSize?: number;
  phase4Progress?: number;
  classDeepseekQueueSize?: number;
  classQwenQueueSize?: number | null;
  classRenameQueueSize?: number;
  currentIteration?: number;
  // PARSE 0 props
  parse0Step?: number;
  parse0Progress?: number;
  apkFilePath?: string;
  outputProjectPath?: string;
  decompileSuccessRate?: number;
  totalResourcesExtracted?: number;
  dependenciesDetected?: number;
}

export default function WorkflowGraph({
  currentPhase = 'Phase 1',
  processedMethods = 0,
  renamedMethods = 0,
  leafMethods = 0,
  batchSize = 2,
  recentLlmRequests = [],
  parsedFiles = 0,
  totalClasses = 0,
  callGraphEdges = 0,
  failedMethods = 0,
  totalFilesToParse = 0,
  phase1Progress = 0,
  phase2Progress = 0,
  phase3Progress = 0,
  callGraphClasses = 0,
  totalCallGraphClasses = 0,
  deepseekQueueSize = 0,
  qwenQueueSize = null,
  renameQueueSize = 0,
  phase4Progress = 0,
  classDeepseekQueueSize = 0,
  classQwenQueueSize = null,
  classRenameQueueSize = 0,
  currentIteration = 1,
  // PARSE 0 defaults
  parse0Step = 0,
  parse0Progress = 0,
  apkFilePath = '',
  outputProjectPath = '',
  decompileSuccessRate = 0,
  totalResourcesExtracted = 0,
  dependenciesDetected = 0,
}: WorkflowGraphProps) {
  // 가로/세로 레이아웃 토글
  const [isVertical, setIsVertical] = useState(false);

  // 현재 처리 중인 요청들
  const analysisRequests = useMemo(() =>
    recentLlmRequests.filter(r => r.requestType === 'analysis').slice(0, batchSize),
    [recentLlmRequests, batchSize]
  );

  const translationRequests = useMemo(() =>
    recentLlmRequests.filter(r => r.requestType === 'translation').slice(0, batchSize),
    [recentLlmRequests, batchSize]
  );

  // Phase 3 활성화 여부에 따라 worker 개수 결정
  const isPhase3Active = currentPhase.includes('PHASE3_AI_ANALYSIS');
  const workerCount = isPhase3Active ? batchSize : 1;  // Phase 3 전에는 1개만

  // 노드 동적 생성
  const initialNodes: Node[] = useMemo(() => {
    const nodes: Node[] = [];

    if (isVertical) {
      // 세로 레이아웃
      const centerX = 700;  // 중심 X 좌표
      const nodeSpacing = 120;  // 노드 간격
      const horizontalSpacing = 150;  // 병렬 노드 가로 간격

      // 1. Source Files
      nodes.push({
        id: 'source',
        type: 'input',
        data: { label: '📁 Source Files' },
        position: { x: centerX, y: 50 },
        sourcePosition: Position.Bottom,
        style: getNodeStyle(currentPhase.includes('PHASE1_FILE_PARSING') ? 'processing' : 'completed'),
      });

      // 2. Phase 1: File Parsing
      const isPhase1Active = currentPhase.includes('PHASE1_FILE_PARSING');
      const parseLabel = isPhase1Active
        ? `📝 Parse Files\nFiles: ${parsedFiles}/${totalFilesToParse}\nProgress: ${phase1Progress.toFixed(1)}%`
        : parsedFiles > 0
        ? `📝 Parse Files\n${parsedFiles} files\n✓ ${phase1Progress.toFixed(0)}%`
        : '📝 Parse Files';

      nodes.push({
        id: 'parse',
        data: { label: parseLabel },
        position: { x: centerX, y: 200 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(isPhase1Active ? 'processing' : phase1Progress > 0 ? 'completed' : 'pending'),
      });

      // 3. Phase 2: Call Graph Building
      const isPhase2Active = currentPhase.includes('PHASE2_CALL_GRAPH');
      const graphLabel = isPhase2Active
        ? `🔗 Build Graph\nClasses: ${callGraphClasses}/${totalCallGraphClasses}\nProgress: ${phase2Progress.toFixed(1)}%`
        : callGraphEdges > 0
        ? `🔗 Build Graph\n${totalClasses} classes\n${callGraphEdges} edges\n✓ ${phase2Progress.toFixed(0)}%`
        : '🔗 Build Graph';

      nodes.push({
        id: 'graph',
        data: { label: graphLabel },
        position: { x: centerX, y: 350 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(isPhase2Active ? 'processing' : phase2Progress > 0 ? 'completed' : 'pending'),
      });

      // 4. Leaf Methods
      const isPhase4Active = currentPhase.includes('PHASE3_AI_ANALYSIS');
      const leafLabel = isPhase4Active
        ? `🎯 Leaf Methods\n${processedMethods}/${leafMethods}\nProgress: ${phase3Progress.toFixed(1)}%`
        : leafMethods > 0
        ? `🎯 Leaf Methods\n${leafMethods} found\n✓ ${phase3Progress.toFixed(0)}%`
        : `🎯 Leaf Methods\nWaiting...`;

      nodes.push({
        id: 'leaf',
        data: { label: leafLabel },
        position: { x: centerX, y: 500 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(
          isPhase4Active && processedMethods < leafMethods ? 'processing' :
          currentPhase === 'COMPLETE' || phase3Progress > 0 ? 'completed' :  // ← Complete 추가
          'pending'
        ),
      });

      // 5a. DeepSeek Queue
      const deepseekQueueLabel = deepseekQueueSize > 0
        ? `📦 DeepSeek Queue\n${deepseekQueueSize} waiting`
        : `📦 DeepSeek Queue\nEmpty`;

      nodes.push({
        id: 'deepseek-queue',
        data: { label: deepseekQueueLabel },
        position: { x: centerX, y: 580 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(deepseekQueueSize > 0 ? 'processing' : 'pending'),
      });

      // 5b. DeepSeek Analysis 노드들 (가로 배치)
      const deepseekY = 660;
      const startX = centerX - ((workerCount - 1) * horizontalSpacing / 2);
      for (let i = 0; i < workerCount; i++) {
        const request = analysisRequests[i];
        const isProcessing = isPhase4Active && request;

        let label = `🤖 DeepSeek ${i + 1}`;
        if (request) {
          const methodShort = request.methodName.length > 20
            ? request.methodName.substring(0, 20) + '...'
            : request.methodName;
          const duration = (request.durationMs / 1000).toFixed(1);
          const status = request.success ? '✓' : '✗';
          label = `🤖 DeepSeek ${i + 1}\n${status} ${methodShort}\n(${duration}s)`;
        }

        nodes.push({
          id: `deepseek-${i}`,
          data: { label },
          position: { x: startX + (i * horizontalSpacing), y: deepseekY },
          sourcePosition: Position.Bottom,
          targetPosition: Position.Top,
          style: getNodeStyle(
            isProcessing ? 'processing' :
            currentPhase.includes('PHASE4_CLASSES') || currentPhase === 'COMPLETE' ? 'completed' :
            'pending'
          ),
        });
      }

      // 6a. Qwen Queue (only if Korean translation is enabled)
      if (qwenQueueSize !== null) {
        const qwenQueueLabel = qwenQueueSize > 0
          ? `📦 Qwen Queue\n${qwenQueueSize} waiting`
          : `📦 Qwen Queue\nEmpty`;

        nodes.push({
          id: 'qwen-queue',
          data: { label: qwenQueueLabel },
          position: { x: centerX, y: 750 },
          sourcePosition: Position.Bottom,
          targetPosition: Position.Top,
          style: getNodeStyle(qwenQueueSize > 0 ? 'processing' : 'pending'),
        });

        // 6b. Qwen Translation 노드들 (가로 배치)
        const qwenY = 830;
        for (let i = 0; i < workerCount; i++) {
          const request = translationRequests[i];
          const isProcessing = isPhase4Active && request;

          let label = `🌏 Qwen ${i + 1}`;
          if (request) {
            const methodShort = request.methodName.length > 20
              ? request.methodName.substring(0, 20) + '...'
              : request.methodName;
            const duration = (request.durationMs / 1000).toFixed(1);
            const status = request.success ? '✓' : '✗';
            label = `🌏 Qwen ${i + 1}\n${status} ${methodShort}\n(${duration}s)`;
          }

          nodes.push({
            id: `qwen-${i}`,
            data: { label },
            position: { x: startX + (i * horizontalSpacing), y: qwenY },
            sourcePosition: Position.Bottom,
            targetPosition: Position.Top,
            style: getNodeStyle(isProcessing ? 'processing' : renamedMethods > 0 ? 'completed' : 'pending'),
          });
        }
      }

      // 7a. Rename Queue
      const renameQueueLabel = renameQueueSize > 0
        ? `📦 Rename Queue\n${renameQueueSize} waiting`
        : `📦 Rename Queue\nEmpty`;

      nodes.push({
        id: 'rename-queue',
        data: { label: renameQueueLabel },
        position: { x: centerX, y: 920 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(renameQueueSize > 0 ? 'processing' : 'pending'),
      });

      // 7b. Renamed (집계)
      const successRate = processedMethods > 0
        ? ((renamedMethods / processedMethods) * 100).toFixed(1)
        : '0.0';
      const renamedLabel = renamedMethods > 0
        ? `✨ Renamed\n${renamedMethods} methods\nSuccess: ${successRate}%${failedMethods > 0 ? `\nFailed: ${failedMethods}` : ''}`
        : '✨ Renamed\nWaiting...';

      nodes.push({
        id: 'renamed',
        data: { label: renamedLabel },
        position: { x: centerX, y: 1000 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(
          currentPhase === 'COMPLETE' || renamedMethods > 0 ? 'completed' : 'pending'  // ← Complete 추가
        ),
      });

      // 7a. Phase 4 Queues - Class DeepSeek Queue
      const classDeepseekQueueLabel = classDeepseekQueueSize > 0
        ? `📦 Class DS Q\n${classDeepseekQueueSize} waiting`
        : `📦 Class DS Q\nEmpty`;

      nodes.push({
        id: 'class-deepseek-queue',
        data: { label: classDeepseekQueueLabel },
        position: { x: centerX, y: 1080 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(classDeepseekQueueSize > 0 ? 'processing' : 'pending'),
      });

      // 7b. Phase 4 Queues - Class Qwen Queue
      const classQwenQueueLabel = classQwenQueueSize !== null && classQwenQueueSize > 0
        ? `📦 Class Qwen Q\n${classQwenQueueSize} waiting`
        : `📦 Class Qwen Q\nEmpty`;

      nodes.push({
        id: 'class-qwen-queue',
        data: { label: classQwenQueueLabel },
        position: { x: centerX, y: 1160 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(classQwenQueueSize !== null && classQwenQueueSize > 0 ? 'processing' : 'pending'),
      });

      // 7c. Phase 4 Queues - Class Rename Queue
      const classRenameQueueLabel = classRenameQueueSize > 0
        ? `📦 Class Rename Q\n${classRenameQueueSize} waiting`
        : `📦 Class Rename Q\nEmpty`;

      nodes.push({
        id: 'class-rename-queue',
        data: { label: classRenameQueueLabel },
        position: { x: centerX, y: 1240 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(classRenameQueueSize > 0 ? 'processing' : 'pending'),
      });

      // 8. Phase 4: Class Renaming (Final)
      const isPhase4ClassActive = currentPhase.includes('PHASE4_CLASSES');
      const classLabel = isPhase4ClassActive
        ? `🏗️ Phase 4\nRenaming Classes...`
        : currentPhase === 'COMPLETE' || currentPhase.includes('PHASE5')
        ? `🏗️ Phase 4\nClasses Renamed`
        : `🏗️ Phase 4\nClass Renaming`;

      nodes.push({
        id: 'phase4',
        data: { label: classLabel },
        position: { x: centerX, y: 1320 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(
          isPhase4ClassActive ? 'processing' :
          currentPhase === 'COMPLETE' || currentPhase.includes('PHASE5') ? 'completed' :
          'pending'
        ),
      });

      // 9. Phase 5: Save Results
      const isPhase5Active = currentPhase.includes('PHASE5_SAVE');
      const saveLabel = isPhase5Active
        ? `💾 Phase 5\nSaving Results...`
        : currentPhase === 'COMPLETE'
        ? `💾 Phase 5\nResults Saved`
        : `💾 Phase 5\nSave Results`;

      nodes.push({
        id: 'phase5',
        type: 'output',
        data: { label: saveLabel },
        position: { x: centerX, y: 1450 },
        targetPosition: Position.Top,
        style: getNodeStyle(
          isPhase5Active ? 'processing' :
          currentPhase === 'COMPLETE' ? 'completed' :
          'pending'
        ),
      });

    } else {
      // 가로 레이아웃 (간소화된 버전 - 워커들을 요약 노드로 통합)
      const centerY = 300;
      const nodeSpacing = 220;  // 노드 간 간격 증가

      // PARSE 0: APK → Gradle Project (요약 노드)
      const isParse0Active = currentPhase.includes('PARSE0_');
      let parse0Label = '📦 APK → Gradle';

      if (parse0Step > 0) {
        const stepNames = ['', 'Manifest', 'Decompile', 'Resources', 'Dependencies', 'Project'];
        parse0Label = isParse0Active
          ? `📦 APK → Gradle\nStep ${parse0Step}/5\n${stepNames[parse0Step] || 'Processing'}\n${parse0Progress.toFixed(0)}%`
          : `📦 APK → Gradle\n✓ Complete`;
      }

      nodes.push({
        id: 'parse0',
        type: 'input',
        data: { label: parse0Label },
        position: { x: 50, y: centerY },
        sourcePosition: Position.Right,
        style: getNodeStyle(isParse0Active ? 'processing' : parse0Progress > 0 ? 'completed' : 'pending'),
      });

      // 1. Source Files (PARSE 1 시작)
      nodes.push({
        id: 'source',
        data: { label: '📁 Source Files' },
        position: { x: 270, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(currentPhase.includes('PHASE1_FILE_PARSING') ? 'processing' : parse0Progress > 0 ? 'completed' : 'pending'),
      });

      // 2. Phase 1: File Parsing
      const isPhase1Active = currentPhase.includes('PHASE1_FILE_PARSING');
      const parseLabel = isPhase1Active
        ? `📝 Parse Files\n${parsedFiles}/${totalFilesToParse}\n${phase1Progress.toFixed(1)}%`
        : parsedFiles > 0
        ? `📝 Parse Files\n${parsedFiles} files ✓`
        : '📝 Parse Files';

      nodes.push({
        id: 'parse',
        data: { label: parseLabel },
        position: { x: 490, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(isPhase1Active ? 'processing' : phase1Progress > 0 ? 'completed' : 'pending'),
      });

      // 3. Phase 2: Call Graph Building
      const isPhase2Active = currentPhase.includes('PHASE2_CALL_GRAPH');
      const graphLabel = isPhase2Active
        ? `🔗 Call Graph\n${callGraphClasses}/${totalCallGraphClasses}\n${phase2Progress.toFixed(1)}%`
        : callGraphEdges > 0
        ? `🔗 Call Graph\n${totalClasses} cls\n${callGraphEdges} edges ✓`
        : '🔗 Call Graph';

      nodes.push({
        id: 'graph',
        data: { label: graphLabel },
        position: { x: 710, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(isPhase2Active ? 'processing' : phase2Progress > 0 ? 'completed' : 'pending'),
      });

      // 4. Leaf Methods (Phase 3 시작점)
      const isPhase3Active = currentPhase.includes('PHASE3_AI_ANALYSIS');
      const leafLabel = isPhase3Active
        ? `🎯 Methods\n${processedMethods}/${leafMethods}\n${phase3Progress.toFixed(1)}%`
        : leafMethods > 0
        ? `🎯 Methods\n${leafMethods} found ✓`
        : `🎯 Methods\nWaiting...`;

      nodes.push({
        id: 'leaf',
        data: { label: leafLabel },
        position: { x: 930, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(
          isPhase3Active && processedMethods < leafMethods ? 'processing' :
          currentPhase === 'COMPLETE' || phase3Progress > 0 ? 'completed' :  // ← Complete 추가
          'pending'
        ),
      });

      // 5. DeepSeek Workers (요약 노드 - 개별 워커 대신)
      const activeDeepseek = analysisRequests.length;
      const deepseekLabel = isPhase3Active
        ? `🤖 DeepSeek\n${workerCount} workers\n${deepseekQueueSize > 0 ? `Queue: ${deepseekQueueSize}` : `Active: ${activeDeepseek}`}`
        : `🤖 DeepSeek\n${workerCount} workers`;

      nodes.push({
        id: 'deepseek-0',  // ID 유지 (엣지 연결용)
        data: { label: deepseekLabel },
        position: { x: 1150, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(
          isPhase3Active && activeDeepseek > 0 ? 'processing' :
          currentPhase.includes('PHASE4_CLASSES') || currentPhase === 'COMPLETE' ? 'completed' :
          'pending'
        ),
      });

      // 6. Qwen Workers (요약 노드) - only if Korean translation is enabled
      if (qwenQueueSize !== null) {
        const activeQwen = translationRequests.length;
        const qwenLabel = isPhase3Active
          ? `🌏 Qwen\n${workerCount} workers\n${qwenQueueSize > 0 ? `Queue: ${qwenQueueSize}` : `Active: ${activeQwen}`}`
          : `🌏 Qwen\n${workerCount} workers`;

        nodes.push({
          id: 'qwen-0',  // ID 유지 (엣지 연결용)
          data: { label: qwenLabel },
          position: { x: 1370, y: centerY },
          sourcePosition: Position.Right,
          targetPosition: Position.Left,
          style: getNodeStyle(isPhase3Active && activeQwen > 0 ? 'processing' : renamedMethods > 0 ? 'completed' : 'pending'),
        });
      }

      // 7. Renamed (결과 집계)
      const successRate = processedMethods > 0
        ? ((renamedMethods / processedMethods) * 100).toFixed(1)
        : '0.0';
      const renamedLabel = renamedMethods > 0
        ? `✨ Renamed\n${renamedMethods} methods\n${successRate}% success${failedMethods > 0 ? `\n${failedMethods} failed` : ''}`
        : '✨ Renamed\nWaiting...';

      // Qwen이 없으면 Renamed를 더 가까이 배치 (DeepSeek 바로 다음)
      const renamedX = qwenQueueSize !== null ? 1590 : 1370;

      nodes.push({
        id: 'renamed',
        data: { label: renamedLabel },
        position: { x: renamedX, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(
          currentPhase === 'COMPLETE' || renamedMethods > 0 ? 'completed' : 'pending'  // ← Complete 추가
        ),
      });

      // 8. Phase 4: Class Renaming
      const isPhase4ClassActive = currentPhase.includes('PHASE4_CLASSES');
      const classQueueTotal = classDeepseekQueueSize + (classQwenQueueSize ?? 0) + classRenameQueueSize;
      const classLabel = isPhase4ClassActive
        ? `🏗️ Classes\nRenaming...\n${classQueueTotal > 0 ? `Queue: ${classQueueTotal}` : ''}`
        : currentPhase === 'COMPLETE' || currentPhase.includes('PHASE5')
        ? `🏗️ Classes\nRenamed ✓`
        : `🏗️ Classes\nWaiting...`;

      // Phase 4도 Qwen이 없으면 위치 조정
      const phase4X = qwenQueueSize !== null ? 1810 : 1590;

      nodes.push({
        id: 'phase4',
        data: { label: classLabel },
        position: { x: phase4X, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(
          isPhase4ClassActive ? 'processing' :
          currentPhase === 'COMPLETE' || currentPhase.includes('PHASE5') ? 'completed' :
          'pending'
        ),
      });

      // 9. Phase 5: Save Results
      const isPhase5Active = currentPhase.includes('PHASE5_SAVE');
      const saveLabel = isPhase5Active
        ? `💾 Save\nSaving...`
        : currentPhase === 'COMPLETE'
        ? `💾 Save\nComplete ✓`
        : `💾 Save\nWaiting...`;

      // Phase 5도 Qwen이 없으면 위치 조정
      const phase5X = qwenQueueSize !== null ? 2030 : 1810;

      nodes.push({
        id: 'phase5',
        type: 'output',
        data: { label: saveLabel },
        position: { x: phase5X, y: centerY },
        targetPosition: Position.Left,
        style: getNodeStyle(
          isPhase5Active ? 'processing' :
          currentPhase === 'COMPLETE' ? 'completed' :
          'pending'
        ),
      });

      // Hidden queue nodes for edge compatibility (keep IDs but don't display)
      // These are needed because the edge definitions reference these IDs
      nodes.push({
        id: 'deepseek-queue',
        data: { label: '' },
        position: { x: 1150, y: centerY },
        style: { display: 'none', width: 0, height: 0, padding: 0, border: 'none' },
      });
      nodes.push({
        id: 'qwen-queue',
        data: { label: '' },
        position: { x: 1370, y: centerY },
        style: { display: 'none', width: 0, height: 0, padding: 0, border: 'none' },
      });
      nodes.push({
        id: 'rename-queue',
        data: { label: '' },
        position: { x: 1590, y: centerY },
        style: { display: 'none', width: 0, height: 0, padding: 0, border: 'none' },
      });
      nodes.push({
        id: 'class-deepseek-queue',
        data: { label: '' },
        position: { x: 1810, y: centerY },
        style: { display: 'none', width: 0, height: 0, padding: 0, border: 'none' },
      });
      nodes.push({
        id: 'class-qwen-queue',
        data: { label: '' },
        position: { x: 1810, y: centerY },
        style: { display: 'none', width: 0, height: 0, padding: 0, border: 'none' },
      });
      nodes.push({
        id: 'class-rename-queue',
        data: { label: '' },
        position: { x: 1810, y: centerY },
        style: { display: 'none', width: 0, height: 0, padding: 0, border: 'none' },
      });
    }

    return nodes;
  }, [isVertical, currentPhase, processedMethods, renamedMethods, leafMethods, batchSize, analysisRequests, translationRequests, parsedFiles, totalFilesToParse, phase1Progress, callGraphClasses, totalCallGraphClasses, phase2Progress, callGraphEdges, totalClasses, phase3Progress, workerCount, deepseekQueueSize, qwenQueueSize, renameQueueSize, classDeepseekQueueSize, classQwenQueueSize, classRenameQueueSize, parse0Step, parse0Progress]);

  // 엣지 동적 생성 (간소화된 버전)
  const initialEdges: Edge[] = useMemo(() => {
    const edges: Edge[] = [];
    const isPhase3Active = currentPhase.includes('PHASE3_AI_ANALYSIS');
    const isParse0Active = currentPhase.includes('PARSE0_');

    // PARSE 0 → Source
    edges.push({
      id: 'e-parse0-source',
      source: 'parse0',
      target: 'source',
      animated: isParse0Active,
      style: { stroke: '#ff9500' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#ff9500' }
    });

    // Source → Parse
    edges.push({
      id: 'e-source-parse',
      source: 'source',
      target: 'parse',
      animated: currentPhase.includes('PHASE1_FILE_PARSING'),
      style: { stroke: '#00d9ff' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#00d9ff' }
    });

    // Parse → Graph
    edges.push({
      id: 'e-parse-graph',
      source: 'parse',
      target: 'graph',
      animated: currentPhase.includes('PHASE2_CALL_GRAPH'),
      style: { stroke: '#00d9ff' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#00d9ff' }
    });

    // Graph → Leaf
    edges.push({
      id: 'e-graph-leaf',
      source: 'graph',
      target: 'leaf',
      animated: isPhase3Active,
      style: { stroke: '#00d9ff' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#00d9ff' }
    });

    // Leaf → DeepSeek (직접 연결)
    edges.push({
      id: 'e-leaf-deepseek',
      source: 'leaf',
      target: 'deepseek-0',
      animated: isPhase3Active,
      style: { stroke: '#00d9ff' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#00d9ff' }
    });

    // DeepSeek → Qwen (only if Korean translation is enabled)
    // Otherwise, DeepSeek → Renamed directly
    if (qwenQueueSize !== null) {
      edges.push({
        id: 'e-deepseek-qwen',
        source: 'deepseek-0',
        target: 'qwen-0',
        animated: isPhase3Active,
        style: { stroke: '#a855f7' },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#a855f7' }
      });

      // Qwen → Renamed
      edges.push({
        id: 'e-qwen-renamed',
        source: 'qwen-0',
        target: 'renamed',
        animated: isPhase3Active || renamedMethods > 0,
        style: { stroke: '#00ff88' },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#00ff88' }
      });
    } else {
      // DeepSeek → Renamed directly (no Korean translation)
      edges.push({
        id: 'e-deepseek-renamed',
        source: 'deepseek-0',
        target: 'renamed',
        animated: isPhase3Active || renamedMethods > 0,
        style: { stroke: '#00ff88' },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#00ff88' }
      });
    }

    // Renamed → Phase 4
    edges.push({
      id: 'e-renamed-phase4',
      source: 'renamed',
      target: 'phase4',
      animated: currentPhase.includes('PHASE4_CLASSES'),
      style: { stroke: '#ffaa00' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#ffaa00' }
    });

    // Phase 4 → Phase 5
    edges.push({
      id: 'e-phase4-phase5',
      source: 'phase4',
      target: 'phase5',
      animated: currentPhase.includes('PHASE5_SAVE') || currentPhase === 'COMPLETE',
      style: { stroke: '#00ffcc' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#00ffcc' }
    });

    return edges;
  }, [currentPhase, renamedMethods, parse0Step, parse0Progress, qwenQueueSize]);

  const containerHeight = isVertical ? '1600px' : '450px';  // 가로 레이아웃은 컴팩트하게

  return (
    <div style={{ width: '100%', height: containerHeight }} className="bg-slate-900/50 rounded-lg border border-purple-500/20 relative">
      {/* Iteration Badge */}
      <div className="absolute top-4 left-4 z-10">
        <div className={`px-4 py-2 rounded-lg font-semibold text-sm border shadow-lg flex items-center gap-2 ${
          currentIteration > 1
            ? 'bg-cyan-600/80 border-cyan-400/30 text-white'
            : 'bg-slate-700/80 border-slate-500/30 text-gray-300'
        }`}>
          <span className="text-lg">🔄</span>
          <span>Iteration {currentIteration}</span>
          {currentIteration > 1 && (
            <span className="text-xs bg-cyan-500/30 px-2 py-0.5 rounded">Loop</span>
          )}
        </div>
      </div>

      {/* 레이아웃 토글 버튼 */}
      <div className="absolute top-4 right-4 z-10">
        <button
          onClick={() => setIsVertical(!isVertical)}
          className="px-4 py-2 bg-purple-600/80 hover:bg-purple-500 text-white rounded-lg transition-colors duration-200 font-semibold text-sm border border-purple-400/30 shadow-lg"
          title={isVertical ? "가로 레이아웃으로 전환" : "세로 레이아웃으로 전환"}
        >
          {isVertical ? '↔️ 가로' : '↕️ 세로'}
        </button>
      </div>

      <ReactFlow
        nodes={initialNodes}
        edges={initialEdges}
        fitView
        attributionPosition="bottom-left"
        proOptions={{ hideAttribution: true }}
      >
        <Background color="#4b5563" gap={16} />
      </ReactFlow>

      {/* Pipeline Legend - Compact */}
      <div className="absolute bottom-4 left-4 bg-slate-900/30 backdrop-blur-md border border-slate-700/50 rounded-lg px-3 py-2 shadow-xl">
        <div className="flex items-center gap-3 text-xs">
          <span className="text-slate-400 font-medium">Phases:</span>
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded-sm" style={{ backgroundColor: '#ff9500' }}></div>
            <span className="text-slate-300">PARSE0</span>
          </div>
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded-sm" style={{ backgroundColor: '#00d9ff' }}></div>
            <span className="text-slate-300">P1-2</span>
          </div>
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded-sm" style={{ backgroundColor: '#a855f7' }}></div>
            <span className="text-slate-300">P3</span>
          </div>
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded-sm" style={{ backgroundColor: '#ffaa00' }}></div>
            <span className="text-slate-300">P4</span>
          </div>
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded-sm" style={{ backgroundColor: '#00ffcc' }}></div>
            <span className="text-slate-300">P5</span>
          </div>
          <span className="text-slate-600">|</span>
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded-sm border-2 border-cyan-400 bg-cyan-400/30"></div>
            <span className="text-slate-400">Processing</span>
          </div>
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded-sm border-2 border-green-400 bg-green-400/30"></div>
            <span className="text-slate-400">Done</span>
          </div>
        </div>
      </div>
    </div>
  );
}

// 노드 상태별 스타일
function getNodeStyle(status: 'pending' | 'processing' | 'completed') {
  const baseStyle = {
    padding: '12px 20px',
    borderRadius: '10px',
    fontSize: '13px',
    fontWeight: '600',
    minWidth: '160px',
    textAlign: 'center' as const,
    border: '2px solid',
    whiteSpace: 'pre-line' as const,
    lineHeight: '1.5',
  };

  switch (status) {
    case 'processing':
      return {
        ...baseStyle,
        background: 'linear-gradient(135deg, rgba(0,217,255,0.3) 0%, rgba(168,85,247,0.3) 100%)',
        borderColor: '#00d9ff',
        color: '#00d9ff',
        boxShadow: '0 0 20px rgba(0,217,255,0.5)',
      };
    case 'completed':
      return {
        ...baseStyle,
        background: 'linear-gradient(135deg, rgba(0,255,136,0.2) 0%, rgba(0,217,255,0.2) 100%)',
        borderColor: '#00ff88',
        color: '#00ff88',
      };
    case 'pending':
    default:
      return {
        ...baseStyle,
        background: 'rgba(100,116,139,0.15)',
        borderColor: '#64748b',
        color: '#94a3b8',
      };
  }
}
