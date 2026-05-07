/* === Common Types === */

export interface PaginatedResponse<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

export interface ApiError {
  detail?: string
  message?: string
  error?: string
}

export type Status = 'pending' | 'running' | 'success' | 'failed' | 'cancelled' | 'paused' | 'queued'

export interface StageTask {
  stage: string
  status: Status
  total: number
  completed: number
  heartbeatAt?: string
  finishedAt?: string
  errorMessage?: string
}

export interface PipelineStage {
  stage: string
  totalFiles: number
  successFiles: number
  runningFiles: number
  failedFiles: number
  pendingFiles: number
  skippedFiles: number
}

export interface ToastOptions {
  message: string
  duration?: number
}