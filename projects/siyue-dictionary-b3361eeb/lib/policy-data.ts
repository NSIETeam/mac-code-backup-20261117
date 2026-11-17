import { corePolicies, corePolicyDocuments, corePolicySources } from './policies-core';
import { extraPolicies, extraPolicyDocuments, extraPolicySources } from './policies-extra';

export const policyDocuments = [...corePolicyDocuments, ...extraPolicyDocuments];
export const policies = [...corePolicies, ...extraPolicies];
export const policySources = [...corePolicySources, ...extraPolicySources];
