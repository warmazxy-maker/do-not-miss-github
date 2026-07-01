import type { EventItem } from "@/types/events";
import type { PageResponse } from "@/types/achievements";

export interface ScheduleItem {
  id: number;
  itemType: "RESERVATION" | "CHALLENGE" | "AI_PLAN" | "CUSTOM";
  sourceId: number | null;
  title: string;
  startTime: string;
  endTime: string;
  location: string | null;
  notes: string | null;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface Challenge {
  id: number;
  title: string;
  category: string;
  goal: string;
  description: string;
  status: "ACTIVE" | "COMPLETED" | "CANCELLED";
  createdAt: string;
  completedAt: string | null;
  did: string | null;
  learned: string | null;
}

export interface CoachMessage {
  id: number;
  role: "USER" | "ASSISTANT";
  content: string;
  messageDate: string;
  createdAt: string;
}

export interface CoachLog {
  id: number;
  logDate: string;
  title: string;
  summary: string;
  content: string;
  tags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface CoachMemory {
  id: number;
  sourceLogId: number;
  memoryType: string;
  title: string;
  memoryText: string;
  tags: string[];
  strength: number;
  reviewCount: number;
  lastReviewedAt: string | null;
  nextReviewAt: string;
}

export interface Organization {
  id: number;
  name: string;
  type: string;
  summary: string;
  createdAt: string;
}

export interface EventQualityReport {
  eventId: number;
  qualityScore: number;
  qualityLevel: string;
  reviewSuggestion: string;
  difficulty: string;
  summary: string;
  targetStudents: string[];
  prerequisites: string[];
  learningOutcomes: string[];
  extractedTags: string[];
  riskFlags: string[];
  missingFields: string[];
  confidence: number;
}

export type ChallengePage = PageResponse<Challenge>;
export type SocialEvent = EventItem;
