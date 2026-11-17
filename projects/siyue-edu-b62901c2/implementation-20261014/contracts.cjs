"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.errorMetadata = errorMetadata;
exports.isObject = isObject;
/** Safe error metadata extraction without changing the original thrown value. */
function errorMetadata(value) {
    if (typeof value !== 'object' || value === null)
        return {};
    const error = value;
    return { code: typeof error.code === 'string' ? error.code : undefined,
        status: typeof error.status === 'number' ? error.status : undefined,
        message: typeof error.message === 'string' ? error.message : undefined };
}
function isObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}
