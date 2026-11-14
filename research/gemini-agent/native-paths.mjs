import { isAbsolute } from 'node:path';

// Native mode is an explicit grant of all filesystem access held by Aster's UID.
// Keep directory enumeration rooted at the project: adding '/' to getDirectories
// would recursively scan the whole device during initialization. Only the access
// boundary changes; native tools, OS permissions and command approvals stay intact.
export function configureNativePaths(config, enabled) {
  if (!enabled) return;
  const valid = path => typeof path === 'string' && isAbsolute(path) && !path.includes('\0');
  const contexts = new WeakSet();
  const getWorkspaceContext = config.getWorkspaceContext.bind(config);
  config.getWorkspaceContext = () => {
    const workspace = getWorkspaceContext();
    if (!contexts.has(workspace)) {
      contexts.add(workspace);
      workspace.isPathWithinWorkspace = valid;
      workspace.isPathReadable = valid;
    }
    return workspace;
  };
  config.isPathAllowed = valid;
  // Config.validatePathAccess still performs the upstream malformed-path checks.
  // Wrapping the getter also covers scoped contexts used by native subagents.
  config.getWorkspaceContext();
}