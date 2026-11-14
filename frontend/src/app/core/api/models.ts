/** Wire models of the `/ui/api` administration API. They mirror the server DTOs and carry no behaviour. */

export type PublicationPolicy = 'MANUAL' | 'TRUSTED_AUTO' | 'REVIEW_UNKNOWN';

export type Classification =
  | 'SERVER_ONLY'
  | 'CLIENT_REQUIRED'
  | 'CLIENT_OPTIONAL'
  | 'CLIENT_ONLY'
  | 'BOTH_REQUIRED'
  | 'UNKNOWN';

export interface ServerInfo {
  readonly id: string;
  readonly name: string;
  readonly minecraftVersion: string;
  readonly loader: string;
  readonly loaderVersion: string | null;
  readonly inventoryAt: string | null;
  readonly publishedRevision: number | null;
  readonly inventoryStale: boolean;
}

export interface ServerRow {
  readonly info: ServerInfo;
  readonly policy: PublicationPolicy;
}

export interface EntryRow {
  readonly fileName: string;
  readonly sha512: string;
  readonly shortHash: string;
  readonly classification: Classification | null;
}

export interface ManifestRow {
  readonly fileName: string;
  readonly projectId: string;
  readonly versionId: string;
  readonly classification: Classification;
}

export interface CoverageRow {
  readonly instanceId: string;
  readonly instanceName: string;
  readonly status: string;
  readonly appliedRevision: number | null;
  readonly verifiedAt: string | null;
  readonly missingCount: number;
}

export interface ServerCoverage {
  readonly serverId: string;
  readonly manifestRevision: number;
  readonly rows: readonly CoverageRow[];
}

export interface PublicationRecord {
  readonly id: number;
  readonly scanId: string;
  readonly policy: PublicationPolicy;
  readonly outcome: string;
  readonly revision: number | null;
  readonly detail: string;
  readonly actor: string;
  readonly createdAt: string;
}

export interface ServerPage {
  readonly info: ServerInfo;
  readonly policy: PublicationPolicy;
  readonly entries: readonly EntryRow[];
  readonly unreviewed: number;
  readonly manifestRevision: number | null;
  readonly manifest: readonly ManifestRow[];
  readonly coverage: ServerCoverage | null;
  readonly history: readonly PublicationRecord[];
}

export interface UserSummary {
  readonly id: string;
  readonly username: string;
  readonly role: 'ADMIN' | 'CLIENT';
  readonly enabled: boolean;
  readonly manageable: boolean;
}

export interface UserPage {
  readonly users: readonly UserSummary[];
  readonly page: number;
  readonly totalPages: number;
  readonly total: number;
}

export interface UserDetail {
  readonly user: UserSummary;
  readonly activeDevices: number;
}

export interface UserFilter {
  readonly q: string;
  readonly role: '' | 'ADMIN' | 'CLIENT';
  readonly status: '' | 'enabled' | 'disabled';
  readonly page: number;
}

export interface Problem {
  readonly code: string;
  readonly message: string;
}

export const POLICIES: readonly PublicationPolicy[] = ['MANUAL', 'TRUSTED_AUTO', 'REVIEW_UNKNOWN'];

export const CLASSIFICATIONS: readonly Exclude<Classification, 'UNKNOWN'>[] = [
  'SERVER_ONLY',
  'CLIENT_REQUIRED',
  'CLIENT_OPTIONAL',
  'CLIENT_ONLY',
  'BOTH_REQUIRED',
];

export interface ProvidersView {
  readonly providers: readonly string[];
}

export interface IdentitiesView {
  readonly linked: readonly string[];
  readonly available: readonly string[];
}

export interface LinkStart {
  readonly authorizationUrl: string;
}
