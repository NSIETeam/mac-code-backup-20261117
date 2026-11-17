'use strict';
const crmNeeds = require("./java-crm-needs.cjs");
const customerIdentity = require("./java-customer-identity.cjs");
const taskBatch = require("./java-task-batch.cjs");
const taskRouting = require("./java-task-routing.cjs");
const crmOperations = require("./java-crm-operations.cjs");
const crmWorkflows = require("./java-crm-workflows.cjs");
const orders = require("./java-orders.cjs");
const academic = require("./java-academic.cjs");
const savedViews = require("./java-saved-views.cjs");
const consultations = require("./java-consultations.cjs");
const serviceTickets = require("./java-service-tickets.cjs");
const leads = require("./java-leads.cjs");
const opportunities = require("./java-opportunities.cjs");
const leadPolicy = require("./java-lead-policy.cjs");
const campuses = require("./java-campuses.cjs");
const staff = require("./java-staff.cjs");
const dictionary = require("./java-dictionary.cjs");
const settings = require("./java-settings.cjs");
const forms = require("./java-forms.cjs");
const checkouts = require("./java-checkouts.cjs");
const commerce = require("./java-commerce.cjs");
function asRecord(value) { return value; }
function hasPermissions(raw, identity) {
    const permissions = identity.permissions;
    if (Array.isArray(raw.scopes) && !raw.scopes.includes(identity.scope))
        return false;
    if (Array.isArray(raw.permissions))
        return raw.permissions.every((p) => typeof p === 'string' && permissions.includes(p));
    if (typeof raw.permission !== 'string' || !permissions.includes(raw.permission))
        return false;
    if (raw.index === true && identity.scope !== 'all')
        return false;
    if (raw.permission === 'crm.customer.identity.write' && !permissions.includes('crm.customer.read'))
        return false;
    return true;
}
function describe(operations, command, scope, permits) {
    return operations.filter(operation => {
        const scopes = asRecord(operation).scopes;
        return (!Array.isArray(scopes) || scopes.includes(scope)) && permits(operation);
    }).map(operation => {
        const raw = asRecord(operation);
        const permission = typeof raw.permission === 'string' ? raw.permission : Array.isArray(raw.permissions) ? raw.permissions.filter((v) => typeof v === 'string') : undefined;
        const groups = Array.isArray(raw.permissionGroups) ? raw.permissionGroups.filter((g) => Array.isArray(g) && g.every(v => typeof v === 'string')) : undefined;
        const alternatives = Array.isArray(raw.permissionAlternatives) ? raw.permissionAlternatives.filter((g) => Array.isArray(g) && g.every(v => typeof v === 'string')) : undefined;
        const method = typeof raw.method === 'string' ? raw.method : undefined;
        const endpoint = typeof raw.endpoint === 'string' ? raw.endpoint : undefined;
        const path = typeof raw.path === 'string' || Array.isArray(raw.path) ? raw.path : null;
        return { id: String(raw.id), label: typeof raw.label === 'string' ? raw.label : String(raw.name ?? raw.id),
            ...(permission !== undefined ? { permission } : {}), ...(groups ? { permissionGroups: groups } : {}), ...(alternatives ? { permissionAlternatives: alternatives } : {}),
            api: { ...(method ? { method } : {}), path: endpoint ?? path },
            preview: { required: command === 'plan', commits: false }, cli: { command: `${command} ${String(raw.name ?? raw.id)}` } };
    });
}
function list(identity) {
    return [
        ...describe(crmNeeds.operations, 'plan', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(customerIdentity.operations, 'plan', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(taskBatch.operations, 'plan', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(taskRouting.operations, 'plan', identity.scope, op => op.permissions.every(p => identity.permissions.includes(p))),
        ...describe(crmOperations.operations, 'crm', identity.scope, op => crmOperations.allowed(op, identity.permissions)),
        ...describe(crmWorkflows.operations, 'crm', identity.scope, op => crmWorkflows.allowed(op, identity)),
        ...describe(orders.operations, 'orders', identity.scope, op => orders.allowed(op, identity.permissions)),
        ...describe(academic.operations, 'academic', identity.scope, op => academic.allowed(op, identity.permissions)),
        ...describe(savedViews.operations, 'saved-views', identity.scope, op => savedViews.allowed(op, identity.permissions)),
        ...describe(consultations.operations, 'consultations', identity.scope, op => consultations.allowed(op, identity.permissions)),
        ...describe(serviceTickets.operations, 'tickets', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(leads.operations, 'leads', identity.scope, op => leads.allowed(op, identity.permissions)),
        ...describe(opportunities.operations, 'opportunities', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(leadPolicy.operations, 'lead-policy', identity.scope, op => leadPolicy.allowed(op, identity.permissions)),
        ...describe(campuses.operations, 'campuses', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(staff.operations, 'staff', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(dictionary.operations, 'dictionary', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(settings.operations, 'settings', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(forms.operations, 'forms', identity.scope, op => hasPermissions(asRecord(op), identity)),
        ...describe(checkouts.operations, 'checkouts', identity.scope, op => checkouts.allowed(op, identity.permissions)),
        ...describe(commerce.operations, 'commerce', identity.scope, op => commerce.allowed(op, identity.permissions)),
    ];
}
module.exports = { list };
