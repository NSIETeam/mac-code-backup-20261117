import {sqliteTable,text,integer,real,uniqueIndex} from 'drizzle-orm/sqlite-core';
export const employees=sqliteTable('employees',{
 id:text('id').primaryKey(),name:text('name').notNull(),roleIds:text('role_ids').notNull(),
 department:text('department').notNull().default(''),
 campus:text('campus').notNull().default(''),grade:text('grade').notNull().default(''),
 passwordHash:text('password_hash').notNull(),passwordSalt:text('password_salt').notNull(),
 createdAt:text('created_at').notNull(),updatedAt:text('updated_at').notNull(),
});
export const workSessions=sqliteTable('work_sessions',{
 tokenHash:text('token_hash').primaryKey(),employeeId:text('employee_id').notNull(),expiresAt:integer('expires_at').notNull(),
});
export const authAttempts=sqliteTable('auth_attempts',{
 employeeId:text('employee_id').primaryKey(),failures:integer('failures').notNull().default(0),windowStart:integer('window_start').notNull(),lockedUntil:integer('locked_until').notNull().default(0),
});
export const needs=sqliteTable('needs',{
 id:text('id').primaryKey(),requestId:text('request_id').notNull(),ownerId:text('owner_id').notNull(),author:text('author').notNull(),
 title:text('title').notNull(),roleId:text('role_id').notNull(),processId:text('process_id').notNull().default(''),
 campus:text('campus').notNull().default(''),grade:text('grade').notNull().default(''),category:text('category').notNull(),
 problem:text('problem').notNull(),currentMethod:text('current_method').notNull().default(''),desiredOutcome:text('desired_outcome').notNull(),evidence:text('evidence').notNull().default(''),
 frequencyPerWeek:real('frequency_per_week'),minutesPerOccurrence:real('minutes_per_occurrence'),affectedPeople:integer('affected_people'),
 urgency:text('urgency').notNull().default('一般'),status:text('status').notNull().default('待核实'),
 createdAt:text('created_at').notNull(),updatedAt:text('updated_at').notNull(),version:integer('version').notNull().default(1),
},t=>[uniqueIndex('needs_owner_request').on(t.ownerId,t.requestId)]);
