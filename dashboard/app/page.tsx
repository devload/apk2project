'use client';

import { useEffect, useState } from 'react';
import WorkflowGraph from './components/WorkflowGraph';
import SystemMonitor from './components/SystemMonitor';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';

interface ProgressStatus {
  phase: string;
  currentPhase: string;  // PipelinePhase enum 값
  status: string;
  isRunning: boolean;
  totalClasses: number;
  totalMethods: number;
  leafMethods: number;
  processedMethods: number;
  renamedMethods: number;
  failedMethods: number;
  currentIteration: number;
  progress: number;
  elapsedMs: number;
  elapsedFormatted: string;
  methodsPerMinute: number;
  estimatedRemainingMs: number;
  estimatedRemainingFormatted: string;
  estimatedCompletionTime: string;
  successRate: number;
  parsedFiles: number;
  totalFilesToParse: number;
  failedParseFiles: number;
  callGraphEdges: number;
  phase1Progress: number;
  callGraphClasses: number;
  totalCallGraphClasses: number;
  phase2Progress: number;
  aiClientType: string;
  aiClientAvailable: boolean;
  phase3Progress: number;
  deepseekQueueSize: number;
  qwenQueueSize: number;
  renameQueueSize: number;
  phase4Progress: number;
  classDeepseekQueueSize: number;
  classQwenQueueSize: number;
  classRenameQueueSize: number;
  batchSize: number;
  recentRenames: RenameEntry[];
  recentLlmRequests?: LlmRequestEntry[];
  resourceHistory: ResourceSnapshot[];
  cpuUsagePercent: number;
  memoryUsedMb: number;
  memoryTotalMb: number;
  memoryUsagePercent: number;
  lastUpdated: number;
}

interface ResourceSnapshot {
  timestamp: number;
  cpuUsagePercent: number;
  memoryUsedMb: number;
  memoryUsagePercent: number;
}

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

interface RenameEntry {
  type: string;  // "PACKAGE", "CLASS", "METHOD", "FIELD"
  original: string;
  suggested: string;
  description: string;
  reasoning: string;
  className: string;
  fullClassName: string;
  filePath: string;
  lineNumber: number;
  sourceCodeBefore: string;
  sourceCodeAfter: string;
  localVariableRenames?: Record<string, string>;  // 로컬 변수 리네임
  referencesUpdated?: number;  // 참조 업데이트 개수
  updatedFiles?: string[];  // 참조 업데이트된 파일 경로 목록
  timestamp: number;
}

export default function Dashboard() {
  const [status, setStatus] = useState<ProgressStatus | null>(null);
  const [connected, setConnected] = useState(false);
  const [selectedType, setSelectedType] = useState<string>('ALL');  // 필터: ALL, METHOD, CLASS, FIELD, PACKAGE

  useEffect(() => {
    // Polling - API를 통해 status.json 가져오기
    const interval = setInterval(async () => {
      try {
        const res = await fetch('/api/status?' + Date.now());
        const data = await res.json();
        if (data.error) {
          console.error('API error:', data.error);
          setConnected(false);
        } else {
          setStatus(data);
          setConnected(true);
        }
      } catch (error) {
        console.error('Failed to fetch status:', error);
        setConnected(false);
      }
    }, 3000);

    // 초기 로드
    fetch('/api/status?' + Date.now())
      .then(res => res.json())
      .then(data => {
        if (!data.error) setStatus(data);
      })
      .catch(() => setConnected(false));

    return () => clearInterval(interval);
  }, []);

  if (!status) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center">
        <div className="text-white text-2xl">Loading...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 text-white p-8">
      {/* Header */}
      <div className="mb-8">
        <h1 className="text-4xl font-bold mb-2 bg-gradient-to-r from-cyan-400 to-purple-400 bg-clip-text text-transparent">
          APK2Project - Deobfuscation Pipeline
        </h1>
        <div className="flex items-center gap-2">
          <div className={`w-3 h-3 rounded-full ${connected ? 'bg-green-400' : 'bg-red-400'} animate-pulse`}></div>
          <span className="text-sm text-gray-400">
            {connected ? 'Connected' : 'Disconnected'} • Last update: {new Date(status.lastUpdated).toLocaleTimeString()}
          </span>
        </div>
      </div>

      {/* System Resources */}
      <div className="mb-8">
        <h2 className="text-xl font-semibold mb-4 flex items-center gap-2">
          <span>⚡</span> System Resources
        </h2>
        <SystemMonitor
          cpuUsagePercent={status.cpuUsagePercent}
          memoryUsedMb={status.memoryUsedMb}
          memoryTotalMb={status.memoryTotalMb}
          memoryUsagePercent={status.memoryUsagePercent}
          resourceHistory={status.resourceHistory || []}
        />
      </div>

      {/* Status Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        <StatusCard
          title="Phase"
          value={status.phase}
          subtitle={status.status}
          icon="🔄"
        />
        <StatusCard
          title="Progress"
          value={`${status.processedMethods} / ${status.leafMethods}`}
          subtitle={`${status.progress.toFixed(1)}%`}
          icon="📊"
          progress={status.progress}
        />
        <StatusCard
          title="Renamed"
          value={status.renamedMethods.toString()}
          subtitle={`Success: ${status.successRate.toFixed(1)}%`}
          icon="✨"
        />
        <StatusCard
          title="Elapsed Time"
          value={status.elapsedFormatted}
          subtitle={status.estimatedCompletionTime !== '--:--' ? `ETA: ${status.estimatedCompletionTime}` : 'Calculating...'}
          icon="⏱️"
        />
      </div>

      {/* Workflow Visualization */}
      <div className="mb-8">
        <h2 className="text-xl font-semibold mb-4 flex items-center gap-2">
          <span>🔄</span> Pipeline Workflow
        </h2>
        <WorkflowGraph
          currentPhase={status.currentPhase}
          processedMethods={status.processedMethods}
          renamedMethods={status.renamedMethods}
          leafMethods={status.leafMethods}
          batchSize={status.batchSize}
          recentLlmRequests={status.recentLlmRequests}
          parsedFiles={status.parsedFiles}
          totalClasses={status.totalClasses}
          callGraphEdges={status.callGraphEdges}
          failedMethods={status.failedMethods}
          totalFilesToParse={status.totalFilesToParse}
          phase1Progress={status.phase1Progress}
          callGraphClasses={status.callGraphClasses}
          totalCallGraphClasses={status.totalCallGraphClasses}
          phase2Progress={status.phase2Progress}
          deepseekQueueSize={status.deepseekQueueSize}
          qwenQueueSize={status.qwenQueueSize}
          renameQueueSize={status.renameQueueSize}
          phase3Progress={status.phase3Progress}
          phase4Progress={status.phase4Progress}
          classDeepseekQueueSize={status.classDeepseekQueueSize}
          classQwenQueueSize={status.classQwenQueueSize}
          classRenameQueueSize={status.classRenameQueueSize}
        />
      </div>

      {/* Phase 1 Stats */}
      {status.parsedFiles > 0 && (
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-6 mb-8 border border-purple-500/20">
          <h2 className="text-xl font-semibold mb-4 flex items-center gap-2">
            <span>📁</span> Parsing Statistics
          </h2>
          <div className="grid grid-cols-3 gap-4">
            <StatItem label="Parsed Files" value={status.parsedFiles} />
            <StatItem label="Total Classes" value={status.totalClasses} />
            <StatItem label="Call Graph Edges" value={status.callGraphEdges} />
          </div>
        </div>
      )}

      {/* Recent Renames */}
      {status.recentRenames && status.recentRenames.length > 0 && (
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-6 border border-purple-500/20">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-xl font-semibold flex items-center gap-2">
              <span>🔧</span> Recent Renames
            </h2>
            {/* Category Filter */}
            <div className="flex gap-2">
              {['ALL', 'METHOD', 'CLASS', 'FIELD', 'PACKAGE'].map((type) => {
                const count = type === 'ALL'
                  ? status.recentRenames.length
                  : status.recentRenames.filter(r => r.type === type).length;
                return (
                  <button
                    key={type}
                    onClick={() => setSelectedType(type)}
                    className={`px-3 py-1 rounded text-xs font-medium transition-all ${
                      selectedType === type
                        ? 'bg-purple-600 text-white'
                        : 'bg-slate-700/50 text-gray-300 hover:bg-slate-700'
                    }`}
                  >
                    {type} ({count})
                  </button>
                );
              })}
            </div>
          </div>
          <div className="space-y-3">
            {status.recentRenames
              .filter(rename => selectedType === 'ALL' || rename.type === selectedType)
              .map((rename, index) => (
                <RenameCard key={index} rename={rename} />
              ))}
          </div>
        </div>
      )}
    </div>
  );
}

function StatusCard({ title, value, subtitle, icon, progress }: {
  title: string;
  value: string;
  subtitle?: string;
  icon: string;
  progress?: number;
}) {
  return (
    <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-6 border border-purple-500/20 hover:border-purple-400/40 transition-all">
      <div className="flex items-start justify-between mb-2">
        <span className="text-sm text-gray-400">{title}</span>
        <span className="text-2xl">{icon}</span>
      </div>
      <div className="text-2xl font-bold mb-1">{value}</div>
      {subtitle && <div className="text-sm text-gray-400">{subtitle}</div>}
      {progress !== undefined && (
        <div className="mt-3 bg-slate-700 rounded-full h-2 overflow-hidden">
          <div
            className="bg-gradient-to-r from-cyan-400 to-purple-400 h-full transition-all duration-500"
            style={{ width: `${Math.min(progress, 100)}%` }}
          ></div>
        </div>
      )}
    </div>
  );
}

function StatItem({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div className="text-sm text-gray-400 mb-1">{label}</div>
      <div className="text-xl font-semibold">{value.toLocaleString()}</div>
    </div>
  );
}

function RenameCard({ rename }: { rename: RenameEntry }) {
  return (
    <div className="bg-slate-700/50 rounded-lg p-4 border border-slate-600/50 hover:border-purple-400/40 transition-all">
      {/* Class Info */}
      <div className="mb-2">
        <span className="text-xs text-gray-400">📍 Class:</span>
        <span className="ml-2 text-sm font-mono text-cyan-300">{rename.fullClassName || rename.className}</span>
        {rename.lineNumber > 0 && (
          <span className="ml-2 text-xs text-gray-500">:{rename.lineNumber}</span>
        )}
      </div>

      {/* File Path */}
      {rename.filePath && (
        <div className="mb-2">
          <span className="text-xs text-gray-400">📁 File:</span>
          <span className="ml-2 text-xs font-mono text-gray-500">{rename.filePath}</span>
        </div>
      )}

      {/* Method Rename */}
      <div className="mb-2 flex items-center gap-2">
        <span className="text-xs text-gray-400">🔧 Method:</span>
        <span className="font-mono text-red-300">{rename.original}</span>
        <span className="text-gray-500">→</span>
        <span className="font-mono text-green-300 font-semibold">{rename.suggested}</span>
      </div>

      {/* Code Diff */}
      {rename.sourceCodeBefore && (
        <div className="mb-3 bg-slate-800/80 rounded border border-slate-600/30 overflow-hidden">
          <div className="text-xs text-gray-400 px-2 py-1 bg-slate-900/50 border-b border-slate-600/30">
            💻 Code Changes
          </div>
          <div className="grid grid-cols-2 gap-0 text-xs">
            {/* Before */}
            <div className="bg-red-900/10 border-r border-slate-600/30">
              <div className="px-2 py-1 bg-red-900/20 text-red-300 text-xs font-semibold">- Before</div>
              <SyntaxHighlighter
                language="java"
                style={vscDarkPlus}
                showLineNumbers={true}
                startingLineNumber={rename.lineNumber > 0 ? rename.lineNumber : 1}
                customStyle={{
                  margin: 0,
                  padding: '8px',
                  fontSize: '11px',
                  background: 'transparent',
                }}
                lineNumberStyle={{
                  minWidth: '2.5em',
                  paddingRight: '1em',
                  color: '#ef4444',
                  opacity: 0.6,
                }}
              >
                {rename.sourceCodeBefore}
              </SyntaxHighlighter>
            </div>
            {/* After */}
            <div className="bg-green-900/10">
              <div className="px-2 py-1 bg-green-900/20 text-green-300 text-xs font-semibold">+ After</div>
              <SyntaxHighlighter
                language="java"
                style={vscDarkPlus}
                showLineNumbers={true}
                startingLineNumber={rename.lineNumber > 0 ? rename.lineNumber : 1}
                customStyle={{
                  margin: 0,
                  padding: '8px',
                  fontSize: '11px',
                  background: 'transparent',
                }}
                lineNumberStyle={{
                  minWidth: '2.5em',
                  paddingRight: '1em',
                  color: '#22c55e',
                  opacity: 0.6,
                }}
              >
                {rename.sourceCodeAfter}
              </SyntaxHighlighter>
            </div>
          </div>
        </div>
      )}

      {/* Description */}
      <div className="mb-2">
        <span className="text-xs text-gray-400">📝 Description:</span>
        <p className="ml-2 text-sm text-gray-300">{rename.description}</p>
      </div>

      {/* Reasoning */}
      {rename.reasoning && (
        <div className="mb-2">
          <span className="text-xs text-gray-400">💡 Reasoning:</span>
          <p className="ml-2 text-sm text-gray-400 italic">{rename.reasoning}</p>
        </div>
      )}

      {/* Local Variables */}
      {rename.localVariableRenames && Object.keys(rename.localVariableRenames).length > 0 && (
        <div className="mb-2 bg-purple-900/10 rounded px-2 py-1 border border-purple-600/30">
          <span className="text-xs text-purple-400">🔤 Local Variables Renamed ({Object.keys(rename.localVariableRenames).length}):</span>
          <div className="ml-2 mt-1 flex flex-wrap gap-2">
            {Object.entries(rename.localVariableRenames).map(([oldName, newName]) => (
              <span key={oldName} className="text-xs bg-purple-900/30 px-2 py-0.5 rounded border border-purple-600/50">
                <span className="text-red-400">{oldName}</span>
                {' → '}
                <span className="text-green-400">{newName}</span>
              </span>
            ))}
          </div>
        </div>
      )}

      {/* References Updated */}
      {rename.referencesUpdated && rename.referencesUpdated > 0 && (
        <div className="bg-blue-900/10 rounded px-2 py-1 border border-blue-600/30">
          <span className="text-xs text-blue-400">🔗 References Updated: {rename.referencesUpdated} file(s)</span>
          {rename.updatedFiles && rename.updatedFiles.length > 0 && (
            <div className="mt-1 ml-2 text-xs text-blue-300/70 font-mono">
              {rename.updatedFiles.slice(0, 3).map((file, idx) => (
                <div key={idx}>• {file}</div>
              ))}
              {rename.updatedFiles.length > 3 && (
                <div className="text-blue-400/50">... and {rename.updatedFiles.length - 3} more</div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
