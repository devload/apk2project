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
  qwenQueueSize?: number;
  renameQueueSize?: number;
  phase4Progress?: number;
  classDeepseekQueueSize?: number;
  classQwenQueueSize?: number;
  classRenameQueueSize?: number;
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
  qwenQueueSize = 0,
  renameQueueSize = 0,
  phase4Progress = 0,
  classDeepseekQueueSize = 0,
  classQwenQueueSize = 0,
  classRenameQueueSize = 0,
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
        style: getNodeStyle(isPhase4Active && processedMethods < leafMethods ? 'processing' : phase3Progress > 0 ? 'completed' : 'pending'),
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

      // 6a. Qwen Queue
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
        style: getNodeStyle(renamedMethods > 0 ? 'completed' : 'pending'),
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
      const classQwenQueueLabel = classQwenQueueSize > 0
        ? `📦 Class Qwen Q\n${classQwenQueueSize} waiting`
        : `📦 Class Qwen Q\nEmpty`;

      nodes.push({
        id: 'class-qwen-queue',
        data: { label: classQwenQueueLabel },
        position: { x: centerX, y: 1160 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        style: getNodeStyle(classQwenQueueSize > 0 ? 'processing' : 'pending'),
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
      // 가로 레이아웃 (기존)
      const centerY = 400;
      const nodeSpacing = 120;

      // 1. Source Files
      nodes.push({
        id: 'source',
        type: 'input',
        data: { label: '📁 Source Files' },
        position: { x: 50, y: centerY },
        sourcePosition: Position.Right,
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
        position: { x: 280, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
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
        position: { x: 510, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
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
        position: { x: 740, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(isPhase4Active && processedMethods < leafMethods ? 'processing' : phase3Progress > 0 ? 'completed' : 'pending'),
      });

      // 5a. DeepSeek Queue
      const deepseekQueueLabelH = deepseekQueueSize > 0
        ? `📦 Q1\n${deepseekQueueSize}`
        : `📦 Q1`;

      nodes.push({
        id: 'deepseek-queue',
        data: { label: deepseekQueueLabelH },
        position: { x: 900, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(deepseekQueueSize > 0 ? 'processing' : 'pending'),
      });

      // 5b. DeepSeek Analysis 노드들 (세로 배치)
      const startY = centerY - ((workerCount - 1) * nodeSpacing / 2);
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
          position: { x: 1100, y: startY + (i * nodeSpacing) },
          sourcePosition: Position.Right,
          targetPosition: Position.Left,
          style: getNodeStyle(
            isProcessing ? 'processing' :
            currentPhase.includes('PHASE4_CLASSES') || currentPhase === 'COMPLETE' ? 'completed' :
            'pending'
          ),
        });
      }

      // 6a. Qwen Queue
      const qwenQueueLabelH = qwenQueueSize > 0
        ? `📦 Q2\n${qwenQueueSize}`
        : `📦 Q2`;

      nodes.push({
        id: 'qwen-queue',
        data: { label: qwenQueueLabelH },
        position: { x: 1260, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(qwenQueueSize > 0 ? 'processing' : 'pending'),
      });

      // 6b. Qwen Translation 노드들 (세로 배치)
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
          position: { x: 1460, y: startY + (i * nodeSpacing) },
          sourcePosition: Position.Right,
          targetPosition: Position.Left,
          style: getNodeStyle(isProcessing ? 'processing' : renamedMethods > 0 ? 'completed' : 'pending'),
        });
      }

      // 7a. Rename Queue
      const renameQueueLabelH = renameQueueSize > 0
        ? `📦 Q3\n${renameQueueSize}`
        : `📦 Q3`;

      nodes.push({
        id: 'rename-queue',
        data: { label: renameQueueLabelH },
        position: { x: 1620, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
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
        position: { x: 1820, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(renamedMethods > 0 ? 'completed' : 'pending'),
      });

      // 7d. Phase 4 Class Queues - DeepSeek Queue
      const classDeepseekQueueLabelH = classDeepseekQueueSize > 0
        ? `📦 CQ1\n${classDeepseekQueueSize}`
        : `📦 CQ1`;

      nodes.push({
        id: 'class-deepseek-queue',
        data: { label: classDeepseekQueueLabelH },
        position: { x: 1900, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(classDeepseekQueueSize > 0 ? 'processing' : 'pending'),
      });

      // 7e. Phase 4 Class Queues - Qwen Queue
      const classQwenQueueLabelH = classQwenQueueSize > 0
        ? `📦 CQ2\n${classQwenQueueSize}`
        : `📦 CQ2`;

      nodes.push({
        id: 'class-qwen-queue',
        data: { label: classQwenQueueLabelH },
        position: { x: 1950, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: getNodeStyle(classQwenQueueSize > 0 ? 'processing' : 'pending'),
      });

      // 7f. Phase 4 Class Queues - Rename Queue
      const classRenameQueueLabelH = classRenameQueueSize > 0
        ? `📦 CQ3\n${classRenameQueueSize}`
        : `📦 CQ3`;

      nodes.push({
        id: 'class-rename-queue',
        data: { label: classRenameQueueLabelH },
        position: { x: 2000, y: centerY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
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
        position: { x: 2100, y: centerY },
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
        ? `💾 Phase 5\nSaving Results...`
        : currentPhase === 'COMPLETE'
        ? `💾 Phase 5\nResults Saved`
        : `💾 Phase 5\nSave Results`;

      nodes.push({
        id: 'phase5',
        type: 'output',
        data: { label: saveLabel },
        position: { x: 2330, y: centerY },
        targetPosition: Position.Left,
        style: getNodeStyle(
          isPhase5Active ? 'processing' :
          currentPhase === 'COMPLETE' ? 'completed' :
          'pending'
        ),
      });
    }

    return nodes;
  }, [isVertical, currentPhase, processedMethods, renamedMethods, leafMethods, batchSize, analysisRequests, translationRequests, parsedFiles, totalFilesToParse, phase1Progress, callGraphClasses, totalCallGraphClasses, phase2Progress, callGraphEdges, totalClasses, phase3Progress, workerCount, deepseekQueueSize, qwenQueueSize, renameQueueSize, classDeepseekQueueSize, classQwenQueueSize, classRenameQueueSize]);

  // 엣지 동적 생성
  const initialEdges: Edge[] = useMemo(() => {
    const edges: Edge[] = [];

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
      animated: currentPhase.includes('PHASE3_AI_ANALYSIS'),
      style: { stroke: '#00d9ff' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#00d9ff' }
    });

    // Leaf → DeepSeek Queue
    edges.push({
      id: 'e-leaf-deepseek-queue',
      source: 'leaf',
      target: 'deepseek-queue',
      animated: currentPhase.includes('PHASE3_AI_ANALYSIS') && deepseekQueueSize > 0,
      style: { stroke: '#00d9ff' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#00d9ff' }
    });

    // DeepSeek Queue → DeepSeek Workers
    for (let i = 0; i < workerCount; i++) {
      edges.push({
        id: `e-deepseek-queue-worker-${i}`,
        source: 'deepseek-queue',
        target: `deepseek-${i}`,
        animated: currentPhase.includes('PHASE3_AI_ANALYSIS'),
        style: { stroke: '#00d9ff' },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#00d9ff' }
      });
    }

    // DeepSeek Workers → Qwen Queue
    for (let i = 0; i < workerCount; i++) {
      edges.push({
        id: `e-deepseek-qwen-queue-${i}`,
        source: `deepseek-${i}`,
        target: 'qwen-queue',
        animated: currentPhase.includes('PHASE3_AI_ANALYSIS'),
        style: { stroke: '#a855f7' },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#a855f7' }
      });
    }

    // Qwen Queue → Qwen Workers
    for (let i = 0; i < workerCount; i++) {
      edges.push({
        id: `e-qwen-queue-worker-${i}`,
        source: 'qwen-queue',
        target: `qwen-${i}`,
        animated: currentPhase.includes('PHASE3_AI_ANALYSIS'),
        style: { stroke: '#a855f7' },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#a855f7' }
      });
    }

    // Qwen Workers → Rename Queue
    for (let i = 0; i < workerCount; i++) {
      edges.push({
        id: `e-qwen-rename-queue-${i}`,
        source: `qwen-${i}`,
        target: 'rename-queue',
        animated: currentPhase.includes('PHASE3_AI_ANALYSIS'),
        style: { stroke: '#00ff88' },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#00ff88' }
      });
    }

    // Rename Queue → Renamed
    edges.push({
      id: 'e-rename-queue-renamed',
      source: 'rename-queue',
      target: 'renamed',
      animated: renamedMethods > 0,
      style: { stroke: '#00ff88' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#00ff88' }
    });

    // Renamed → Class DeepSeek Queue
    edges.push({
      id: 'e-renamed-class-deepseek-queue',
      source: 'renamed',
      target: 'class-deepseek-queue',
      animated: currentPhase.includes('PHASE4_CLASSES') && classDeepseekQueueSize > 0,
      style: { stroke: '#ffaa00' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#ffaa00' }
    });

    // Class DeepSeek Queue → Class Qwen Queue
    edges.push({
      id: 'e-class-deepseek-queue-class-qwen-queue',
      source: 'class-deepseek-queue',
      target: 'class-qwen-queue',
      animated: currentPhase.includes('PHASE4_CLASSES') && classQwenQueueSize > 0,
      style: { stroke: '#ffaa00' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#ffaa00' }
    });

    // Class Qwen Queue → Class Rename Queue
    edges.push({
      id: 'e-class-qwen-queue-class-rename-queue',
      source: 'class-qwen-queue',
      target: 'class-rename-queue',
      animated: currentPhase.includes('PHASE4_CLASSES') && classRenameQueueSize > 0,
      style: { stroke: '#ffaa00' },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#ffaa00' }
    });

    // Class Rename Queue → Phase 4
    edges.push({
      id: 'e-class-rename-queue-phase4',
      source: 'class-rename-queue',
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
  }, [currentPhase, workerCount, renamedMethods, deepseekQueueSize, qwenQueueSize, renameQueueSize, classDeepseekQueueSize, classQwenQueueSize, classRenameQueueSize]);

  const containerHeight = isVertical ? '1600px' : '900px';  // 세로 레이아웃 높이 증가 (큐 + Phase 4, 5 추가)

  return (
    <div style={{ width: '100%', height: containerHeight }} className="bg-slate-900/50 rounded-lg border border-purple-500/20 relative">
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
        <Controls className="bg-slate-800 border-purple-500/30" />
        <Background color="#4b5563" gap={16} />
      </ReactFlow>
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
