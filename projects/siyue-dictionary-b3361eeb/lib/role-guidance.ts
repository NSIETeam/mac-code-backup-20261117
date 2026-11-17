import { managementGuidance } from './management-guidance';
import { staffGuidance } from './staff-guidance';

export type RoleGuidanceItem = { title: string; text: string };
export type RoleGuidance = {
  summary: string;
  requirements: RoleGuidanceItem[];
  checks: RoleGuidanceItem[];
  handoffs: RoleGuidanceItem[];
  questions: string[];
};

// Keep proposed improvements separate from the source-backed role entries.
export const roleGuidance: Readonly<Record<string, RoleGuidance>> = {
  ...staffGuidance,
  ...managementGuidance,
};

export function isManagementGuidance(roleId: string) {
  return Object.prototype.hasOwnProperty.call(managementGuidance, roleId);
}
