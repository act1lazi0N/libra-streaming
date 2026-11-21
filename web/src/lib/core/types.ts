// DTOs mirroring contracts/http/core-*.v1.* (camelCase, as served by Core).

export type Role = "USER" | "ADMIN";

export interface AccountView {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  emailVerified: boolean;
}

export interface SessionResult {
  accessExpiresAt: string;
  sessionExpiresAt: string;
}

export interface CsrfView {
  headerName: string;
  token: string;
}

export interface Profile {
  id: string;
  name: string;
  isDefault: boolean;
  version: number;
}

export type ContentKind = "MOVIE" | "SERIES" | "SEASON" | "EPISODE";
export type AccessTier = "FREE" | "PREMIUM";

export interface ContentMetadata {
  title: string;
  description: string;
  genres: string[];
  releaseYear: number;
  language: string;
  credits: string[];
  imageReferences: string[];
  accessTier: AccessTier;
}

export interface PublicContent {
  id: string;
  kind: ContentKind;
  parentId: string | null;
  ordinal: number | null;
  revision: number;
  metadata: ContentMetadata;
  mediaReady: boolean;
  durationSeconds: number | null;
  publishedAt: string;
}

export interface Eligibility {
  eligible: true;
  accessTier: AccessTier;
  checkedAt: string;
}
