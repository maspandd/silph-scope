#!/usr/bin/env node
// Native checks that need only a JDK (no Android SDK, no Gradle):
//   node scripts/native.mjs test     JUnit tests for the pure-Java TouchGuard
//   node scripts/native.mjs compile  javac every Android source (plugin + overlay-attacker) against
//                                    the Android framework (Robolectric android-all, API 35) and the
//                                    Cordova framework, catching API and type errors before a real build.
// Artifacts are downloaded once into .cache/native and checked against their published SHA-1.

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const CACHE = join(ROOT, '.cache', 'native');
const OUT = join(ROOT, '.cache', 'native-out');

const CENTRAL = 'https://repo1.maven.org/maven2';
const GOOGLE = 'https://dl.google.com/dl/android/maven2';

const TEST_DEPS = [
    [CENTRAL, 'junit:junit:4.13.2', 'jar'],
    [CENTRAL, 'org.hamcrest:hamcrest-core:1.3', 'jar'],
];

// android-all is ~185 MB; it is only fetched for `compile`.
// The androidx set is the superclass chain of AppCompatActivity, which CordovaInterface returns.
const COMPILE_DEPS = [
    [CENTRAL, 'org.robolectric:android-all:15-robolectric-12650502', 'jar'],
    [CENTRAL, 'org.apache.cordova:framework:15.0.0', 'aar'],
    [GOOGLE, 'androidx.appcompat:appcompat:1.7.1', 'aar'],
    [GOOGLE, 'androidx.fragment:fragment:1.5.4', 'aar'],
    [GOOGLE, 'androidx.activity:activity:1.8.0', 'aar'],
    [GOOGLE, 'androidx.core:core:1.13.0', 'aar'],
    [GOOGLE, 'androidx.lifecycle:lifecycle-common:2.6.1', 'jar'],
    [GOOGLE, 'androidx.lifecycle:lifecycle-runtime:2.6.1', 'aar'],
    [GOOGLE, 'androidx.lifecycle:lifecycle-viewmodel:2.6.1', 'aar'],
    [GOOGLE, 'androidx.savedstate:savedstate:1.2.1', 'aar'],
    [GOOGLE, 'androidx.annotation:annotation:1.3.0', 'jar'],
];

// Matches cordova-android 15's JAVA_SOURCE_COMPATIBILITY.
const JAVA_RELEASE = '11';

function jdkTool(name) {
    const exe = process.platform === 'win32' ? `${name}.exe` : name;
    if (process.env.JAVA_HOME) {
        const candidate = join(process.env.JAVA_HOME, 'bin', exe);
        if (existsSync(candidate)) {
            return candidate;
        }
    }
    return exe;
}

function run(tool, args, options = {}) {
    execFileSync(jdkTool(tool), args, { stdio: 'inherit', ...options });
}

async function fetchBuffer(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`GET ${url} -> HTTP ${response.status}`);
    }
    return Buffer.from(await response.arrayBuffer());
}

/** @returns path of a jar usable on the javac classpath. */
async function resolveArtifact([repo, coords, packaging]) {
    const [group, artifact, version] = coords.split(':');
    const fileName = `${artifact}-${version}.${packaging}`;
    const jarPath = join(CACHE, packaging === 'aar' ? `${artifact}-${version}-classes.jar` : fileName);
    if (existsSync(jarPath)) {
        return jarPath;
    }

    mkdirSync(CACHE, { recursive: true });
    const url = `${repo}/${group.replace(/\./g, '/')}/${artifact}/${version}/${fileName}`;
    console.log(`Downloading ${coords} (${packaging})`);
    const [body, sha1] = await Promise.all([fetchBuffer(url), fetchBuffer(`${url}.sha1`)]);
    const expected = sha1.toString('utf8').trim().split(/\s+/)[0].toLowerCase();
    const actual = createHash('sha1').update(body).digest('hex');
    if (actual !== expected) {
        throw new Error(`SHA-1 mismatch for ${url}: expected ${expected}, got ${actual}`);
    }

    if (packaging !== 'aar') {
        writeFileSync(jarPath, body);
        return jarPath;
    }
    // An .aar is a zip; javac needs the classes.jar inside it. Extract in an empty scratch dir.
    const scratch = mkdtempSync(join(CACHE, 'aar-'));
    try {
        const aarPath = join(scratch, fileName);
        writeFileSync(aarPath, body);
        run('jar', ['xf', aarPath, 'classes.jar'], { cwd: scratch });
        renameSync(join(scratch, 'classes.jar'), jarPath);
    } finally {
        rmSync(scratch, { recursive: true, force: true });
    }
    return jarPath;
}

function javaFiles(dir) {
    const files = [];
    for (const entry of readdirSync(dir)) {
        const path = join(dir, entry);
        if (statSync(path).isDirectory()) {
            files.push(...javaFiles(path));
        } else if (entry.endsWith('.java')) {
            files.push(path);
        }
    }
    return files;
}

function freshDir(path) {
    rmSync(path, { recursive: true, force: true });
    mkdirSync(path, { recursive: true });
    return path;
}

// -proc:none: never run annotation processors found inside downloaded jars.
const JAVAC_FLAGS = ['--release', JAVA_RELEASE, '-proc:none', '-encoding', 'UTF-8', '-Xlint:all,-options,-classfile', '-Werror'];

async function test() {
    const classpath = await Promise.all(TEST_DEPS.map(resolveArtifact));
    const out = freshDir(join(OUT, 'test'));
    run('javac', [
        ...JAVAC_FLAGS,
        '-d', out,
        '-cp', classpath.join(delimiter),
        join(ROOT, 'src', 'android', 'TouchGuard.java'),
        ...javaFiles(join(ROOT, 'tests', 'native')),
    ]);
    run('java', ['-cp', [out, ...classpath].join(delimiter), 'org.junit.runner.JUnitCore', 'com.tapjackos.protection.TouchGuardTest']);
}

async function compile() {
    const classpath = [];
    for (const dep of COMPILE_DEPS) {
        classpath.push(await resolveArtifact(dep));
    }
    const sources = [
        ...javaFiles(join(ROOT, 'src', 'android')),
        ...javaFiles(join(ROOT, 'tests', 'overlay-attacker', 'app', 'src', 'main', 'java')),
    ];
    const out = freshDir(join(OUT, 'compile'));
    run('javac', [...JAVAC_FLAGS, '-d', out, '-cp', classpath.join(delimiter), ...sources]);
    console.log(`Compiled ${sources.length} Android source files against API 35 + cordova-android 15.`);
}

const commands = { test, compile };
const command = commands[process.argv[2]];
if (!command) {
    console.error('usage: node scripts/native.mjs <test|compile>');
    process.exit(2);
}
try {
    await command();
} catch (error) {
    if (error.status === undefined) {
        console.error(error.message);
    }
    process.exit(1);
}
