const configuredVersion = import.meta.env.VITE_APP_VERSION?.trim().replace(/^v/i, "");

/** The UI version follows the release package version and can be overridden by a release build. */
export const APP_VERSION = configuredVersion || "0.1.0";
