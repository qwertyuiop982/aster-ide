import { homedir } from 'node:os';
import { join } from 'node:path';
export const APP_NAME = 'Aster Agent';
export function getAgentDir() { return process.env.ASTER_AGENT_HOME || join(homedir(), '.aster', 'agent'); }
export function getSessionsDir() { return join(getAgentDir(), 'sessions'); }
