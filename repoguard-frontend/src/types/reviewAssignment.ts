export interface ReviewAssignmentOptions {
  taskId: number;
  attemptId: number;
  headSha: string;
  assignmentVersion: string;
  members: { userId: number; username: string }[];
  hasMore: boolean;
}
export interface ReviewMemberAssignmentRequest {
  userId: number;
  attemptId: number;
  headSha: string;
  assignmentVersion: string;
}
export interface ReviewMemberAssignment {
  taskId: number;
  attemptId: number;
  headSha: string;
  assignee: string;
}
