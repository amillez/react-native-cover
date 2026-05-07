const { getDefaultConfig } = require("expo/metro-config");
const path = require("node:path");

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, "..");

const config = getDefaultConfig(projectRoot);

// Watch the parent module so live edits to src/ are picked up.
config.watchFolders = [workspaceRoot];

// Block the linked module's own node_modules so we never resolve duplicates of
// react / react-native / nitro-modules from there. Single source of truth:
// example/node_modules.
const moduleNodeModules = path.resolve(workspaceRoot, "node_modules");
const peerDeps = ["react", "react-native", "react-native-nitro-modules"];

const escapeForRegex = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

config.resolver.blockList = peerDeps.map(
  (name) =>
    new RegExp(`^${escapeForRegex(path.join(moduleNodeModules, name))}\\/.*$`),
);

config.resolver.extraNodeModules = peerDeps.reduce((acc, name) => {
  acc[name] = path.join(projectRoot, "node_modules", name);
  return acc;
}, {});

module.exports = config;
