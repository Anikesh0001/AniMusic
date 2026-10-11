// AniMusic download page: reads the latest GitHub release and points each visitor
// at the right file. File names follow the release contract in CLAUDE.md:
// AniMusic-<version>-<platform>.<ext>, all in the same vX.Y.Z release.
"use strict";

const REPO = "Anikesh0001/AniMusic";
const LATEST_API = `https://api.github.com/repos/${REPO}/releases/latest`;
const RELEASES_PAGE = `https://github.com/${REPO}/releases/latest`;

// Every file a release can carry, grouped by platform, most useful first.
const PLATFORMS = [
  {
    id: "android",
    name: "Android",
    files: [
      { id: "apk-arm64", label: "APK for most phones (arm64-v8a)", suffix: "-arm64-v8a.apk" },
      { id: "apk-universal", label: "APK universal (any phone)", suffix: "-universal.apk" },
      { id: "apk-v7a", label: "APK for older 32-bit phones (armeabi-v7a)", suffix: "-armeabi-v7a.apk" },
      { id: "apk-x86_64", label: "APK for x86_64 (emulators, Chromebooks)", suffix: "-x86_64.apk" },
    ],
  },
  {
    id: "mac",
    name: "macOS",
    files: [
      { id: "mac-arm64", label: "Apple Silicon (M1 and newer) .dmg", suffix: "-macos-arm64.dmg" },
      { id: "mac-x64", label: "Intel Mac .dmg", suffix: "-macos-x64.dmg" },
    ],
    note: "Unsure which chip? Apple menu → About This Mac.",
  },
  {
    id: "windows",
    name: "Windows",
    files: [
      { id: "win-setup", label: "Installer (.exe)", suffix: "-windows-x64-setup.exe" },
      { id: "win-msi", label: "Installer (.msi)", suffix: "-windows-x64.msi" },
      { id: "win-portable", label: "Portable (.zip, no install)", suffix: "-windows-x64-portable.zip" },
    ],
    note: "Windows 10 or 11, 64-bit.",
  },
  {
    id: "linux",
    name: "Linux",
    files: [
      { id: "linux-appimage", label: "AppImage (any distro)", suffix: "-linux-x86_64.AppImage" },
      { id: "linux-deb", label: "Debian / Ubuntu (.deb)", suffix: "-linux-amd64.deb" },
      { id: "linux-rpm", label: "Fedora / openSUSE (.rpm)", suffix: "-linux-x86_64.rpm" },
    ],
    note: "64-bit x86 (x86_64) PCs.",
  },
];

/** Release assets as {name, url, size}, keeping only real, finished AniMusic downloads. */
function releaseFiles(release) {
  return (release.assets || [])
    .filter((a) => a && a.state === "uploaded" && typeof a.name === "string" && a.name.startsWith("AniMusic-"))
    .filter((a) => typeof a.browser_download_url === "string" &&
      a.browser_download_url.startsWith(`https://github.com/${REPO}/releases/download/`))
    .map((a) => ({ name: a.name, url: a.browser_download_url, size: a.size || 0 }));
}

/** The asset for one of PLATFORMS' file entries, or null when this release lacks it. */
function findFile(files, entry) {
  return files.find((f) => f.name.toLowerCase().endsWith(entry.suffix.toLowerCase())) || null;
}

/**
 * Which file to recommend: the file ids to try in order for this device.
 * device = {os: "android"|"mac"|"windows"|"linux"|"ios"|null, arch: "arm64"|"x64"|null}
 */
function preferenceFor(device) {
  switch (device.os) {
    case "android": return ["apk-arm64", "apk-universal"];
    // An unknown Mac chip gets Apple Silicon (every Mac sold since 2021), Intel offered right below.
    case "mac": return device.arch === "x64" ? ["mac-x64"] : ["mac-arm64", "mac-x64"];
    case "windows": return ["win-setup", "win-msi", "win-portable"];
    case "linux": return ["linux-appimage", "linux-deb", "linux-rpm"];
    default: return [];
  }
}

function entryById(id) {
  for (const platform of PLATFORMS) {
    const entry = platform.files.find((e) => e.id === id);
    if (entry) return entry;
  }
  return null;
}

/** {platform, entry, file} for the best file this release has for the device, or null. */
function recommend(files, device) {
  for (const id of preferenceFor(device)) {
    for (const platform of PLATFORMS) {
      const entry = platform.files.find((e) => e.id === id);
      if (!entry) continue;
      const file = findFile(files, entry);
      if (file) return { platform, entry, file };
    }
  }
  return null;
}

async function detectDevice() {
  const ua = navigator.userAgent || "";
  if (/Android/i.test(ua)) return { os: "android", arch: null };
  if (/iPhone|iPad|iPod/i.test(ua)) return { os: "ios", arch: null };
  if (/Macintosh|Mac OS X/i.test(ua)) {
    // iPadOS asks for desktop sites with a Mac user agent; only it has a touch screen.
    if (navigator.maxTouchPoints > 1) return { os: "ios", arch: null };
    return { os: "mac", arch: await macArch() };
  }
  if (/Windows/i.test(ua)) return { os: "windows", arch: null };
  if (/CrOS/i.test(ua)) return { os: "android", arch: null }; // Chromebooks run Android apps
  if (/Linux|X11/i.test(ua)) return { os: "linux", arch: null };
  return { os: null, arch: null };
}

/** "arm64", "x64", or null when the browser won't say (Safari and Firefox often don't). */
async function macArch() {
  try {
    if (navigator.userAgentData && navigator.userAgentData.getHighEntropyValues) {
      const v = await navigator.userAgentData.getHighEntropyValues(["architecture"]);
      if (v.architecture === "arm") return "arm64";
      if (v.architecture === "x86") return "x64";
    }
  } catch (_) { /* not offered */ }
  try {
    const gl = document.createElement("canvas").getContext("webgl");
    if (gl) {
      const info = gl.getExtension("WEBGL_debug_renderer_info");
      const renderer = String(gl.getParameter(info ? info.UNMASKED_RENDERER_WEBGL : gl.RENDERER));
      if (/Apple M\d/i.test(renderer)) return "arm64";
      if (/Intel|AMD|Radeon/i.test(renderer)) return "x64";
    }
  } catch (_) { /* no WebGL */ }
  return null;
}

function formatSize(bytes) {
  if (!bytes) return "";
  return `${(bytes / (1024 * 1024)).toFixed(bytes > 100 * 1024 * 1024 ? 0 : 1)} MB`;
}

function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === "class") node.className = v;
    else node.setAttribute(k, v);
  }
  for (const c of children) if (c != null) node.append(c);
  return node;
}

function renderPlatforms(files, device, best) {
  const root = document.getElementById("platforms");
  root.replaceChildren();
  for (const platform of PLATFORMS) {
    const isCurrent = platform.id === device.os;
    const list = el("ul", { class: "files" });
    let found = 0;
    for (const entry of platform.files) {
      const file = files ? findFile(files, entry) : null;
      if (!file) continue;
      found++;
      const isBest = best && best.file === file;
      list.append(el("li", null,
        el("a", { class: "file" + (isBest ? " best" : ""), href: file.url, download: "" },
          el("span", { class: "label" }, entry.label),
          el("span", { class: "size" }, formatSize(file.size)))));
    }
    if (!files) {
      list.append(el("li", null,
        el("a", { class: "file", href: RELEASES_PAGE }, el("span", { class: "label" }, "See the files on GitHub"))));
    } else if (found === 0) {
      list.append(el("li", null,
        el("span", { class: "file missing" }, el("span", { class: "label" }, "Coming with the next release"))));
    }
    const heading = el("h3", null, platform.name, isCurrent ? el("span", { class: "badge" }, "Your device") : null);
    root.append(el("div", { class: "platform" + (isCurrent ? " current" : ""), id: `platform-${platform.id}` },
      heading, list, platform.note ? el("p", { class: "note" }, platform.note) : null));
  }
}

function renderRecommended(best, device) {
  const box = document.getElementById("recommended");
  const label = document.getElementById("rec-label");
  const button = document.getElementById("rec-button");
  const note = document.getElementById("rec-note");
  const hero = document.getElementById("hero-download");

  if (device.os === "ios") {
    label.textContent = "AniMusic isn't available for iPhone or iPad.";
    button.textContent = "See all downloads";
    button.href = "#platforms";
    note.textContent = "It runs on Android, macOS, Windows and Linux.";
    box.hidden = false;
    return;
  }
  if (!best) {
    const platform = PLATFORMS.find((p) => p.id === device.os);
    if (!platform) {
      box.hidden = true;
      return;
    }
    label.textContent = `AniMusic for ${platform.name} isn't in this release yet.`;
    button.textContent = "See all downloads";
    button.href = "#platforms";
    note.textContent = "It comes with the next release. The Android app is available now.";
    box.hidden = false;
    return;
  }
  const platform = best.platform;
  label.textContent = `Recommended for your ${platform.name === "macOS" ? "Mac" : platform.name + " device"}`;
  button.textContent = `Download for ${platform.name} · ${best.entry.label}`;
  button.href = best.file.url;
  button.setAttribute("download", "");
  hero.textContent = `Download for ${platform.name}`;
  hero.href = best.file.url;

  if (platform.id === "mac" && best.entry.id === "mac-arm64" && device.arch !== "arm64") {
    const intel = findFile(lastFiles, entryById("mac-x64"));
    note.replaceChildren(
      "This is for Apple Silicon Macs (M1 and newer). ",
      intel ? el("a", { href: intel.url, download: "" }, "Intel Mac? Download this instead.") : "",
    );
  } else if (platform.id === "android") {
    note.textContent = "Works on almost every phone. If it won't install, use the universal APK below.";
  } else {
    note.textContent = `First time? See the ${platform.name} install help below.`;
  }
  box.hidden = false;

  // Open the matching install help.
  const help = document.getElementById(`help-${platform.id}`);
  if (help) help.open = true;
}

let lastFiles = [];

async function main() {
  const line = document.getElementById("release-line");
  const heroVersion = document.getElementById("hero-version");
  const device = await detectDevice();

  let release;
  try {
    const response = await fetch(LATEST_API, { headers: { Accept: "application/vnd.github+json" } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    release = await response.json();
  } catch (_) {
    line.replaceChildren("Couldn't reach GitHub just now. Pick your file on the ",
      el("a", { href: RELEASES_PAGE }, "latest release page"), ".");
    renderPlatforms(null, device, null);
    return;
  }

  const files = releaseFiles(release);
  lastFiles = files;
  const version = String(release.tag_name || "").replace(/^v/, "");
  const date = release.published_at ? new Date(release.published_at).toLocaleDateString(undefined,
    { year: "numeric", month: "long", day: "numeric" }) : "";
  line.replaceChildren(`Latest version: ${version}${date ? ` · released ${date}` : ""} · `,
    el("a", { href: release.html_url || RELEASES_PAGE }, "What's new"));
  heroVersion.textContent = `Version ${version}`;

  const best = recommend(files, device);
  renderPlatforms(files, device, best);
  renderRecommended(best, device);
}

function setupCopyButtons() {
  for (const button of document.querySelectorAll("button.copy")) {
    button.addEventListener("click", async () => {
      const text = document.getElementById(button.dataset.copy).textContent;
      try {
        await navigator.clipboard.writeText(text);
        button.textContent = "Copied";
      } catch (_) {
        button.textContent = "Select and copy";
      }
      setTimeout(() => { button.textContent = "Copy"; }, 2000);
    });
  }
}

if (typeof document !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => {
    setupCopyButtons();
    main();
  });
}

// Lets Node load the picking logic for a quick check; the browser ignores this.
if (typeof module !== "undefined") {
  module.exports = { PLATFORMS, releaseFiles, findFile, preferenceFor, recommend };
}
