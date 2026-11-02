import json
from contextlib import redirect_stdout
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

from record_toolchain import executable, main, record, revision_record, version


class RecordToolchainTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.revision = {"commit": "a1" * 20, "dirty": False, "changedPaths": 0, "stateSha256": "b2" * 32}
        self.enterContext(mock.patch("record_toolchain.revision_record", return_value=self.revision))
        self.properties = self.root / ".mvn/wrapper/maven-wrapper.properties"
        self.properties.parent.mkdir(parents=True)
        self.properties.write_text(
            "wrapperVersion=3.3.4\ndistributionUrl=https://example.test:8443/maven.zip\n",
            encoding="utf-8")
        self.output = self.root / "target/verification/toolchain.json"
        self.environment = self.enterContext(mock.patch.dict(os.environ, {}, clear=True))
        self.which = self.enterContext(mock.patch("record_toolchain.shutil.which", side_effect=lambda name: name))
        def version_result(command, **kwargs):
            tool = Path(command[0]).stem
            line = 'openjdk version "21"' if tool == "java" else f"{tool} version 1"
            return subprocess.CompletedProcess(command, 0, line + "\n", "")

        self.run = self.enterContext(mock.patch("record_toolchain.subprocess.run", side_effect=version_result))

    def test_missing_tool_on_path(self):
        self.which.return_value = None
        self.which.side_effect = None
        with self.assertRaisesRegex(ValueError, "Missing tool on PATH: java"):
            record(self.root)
        self.run.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_unavailable_tool(self):
        self.run.side_effect = FileNotFoundError
        with self.assertRaisesRegex(ValueError, "Missing or unavailable tool: java"):
            record(self.root)

    def test_nonzero_version_exit_removes_previous_evidence(self):
        self.output.parent.mkdir(parents=True)
        self.output.write_text("{}", encoding="utf-8")
        self.run.side_effect = None
        self.run.return_value = subprocess.CompletedProcess([], 1, "fake version 1", "")
        with self.assertRaisesRegex(ValueError, "Version command failed: java"):
            record(self.root)
        self.assertFalse(self.output.exists())

    def test_configured_path_must_be_file(self):
        for name, variable in (("ffmpeg", "LIBRA_FFMPEG_PATH"), ("ffprobe", "LIBRA_FFPROBE_PATH")):
            for path in (str(self.root), str(self.root / "missing")):
                with self.subTest(tool=name, path=path), mock.patch.dict(os.environ, {variable: path}):
                    with self.assertRaisesRegex(ValueError, "Configured tool is not an existing file: " + name):
                        executable(name, variable)

    def test_blank_media_paths_fall_back(self):
        for blank in ("", " \t "):
            with self.subTest(value=blank), mock.patch.dict(os.environ, {
                    "LIBRA_FFMPEG_PATH": blank, "LIBRA_FFPROBE_PATH": blank}):
                record(self.root)
                self.assertTrue(self.output.exists())
                self.assertIn(mock.call("ffmpeg"), self.which.call_args_list)
                self.assertIn(mock.call("ffprobe"), self.which.call_args_list)

    def test_ffprobe_uses_first_regular_file_on_path(self):
        filename = "ffprobe.exe" if os.name == "nt" else "ffprobe"
        directories = [self.root / name for name in ("directory", "first", "second")]
        for directory in directories:
            directory.mkdir()
        (directories[0] / filename).mkdir()
        for directory in directories[1:]:
            (directory / filename).write_bytes(b"inert fixture; never executed")
        self.environment["PATH"] = os.pathsep.join(str(path) for path in directories)
        self.assertEqual(executable("ffprobe", "LIBRA_FFPROBE_PATH"), str(directories[1] / filename))
        self.which.assert_not_called()

    def test_ffmpeg_beside_ffprobe(self):
        ffprobe = self.root / ("ffprobe.exe" if os.name == "nt" else "ffprobe")
        ffmpeg = self.root / ("ffmpeg.exe" if os.name == "nt" else "ffmpeg")
        for path in (ffprobe, ffmpeg):
            path.write_bytes(b"inert fixture; never executed")
        self.environment["LIBRA_FFPROBE_PATH"] = str(ffprobe)
        record(self.root)
        self.assertEqual(self.run.call_args_list[3].args[0], [str(ffmpeg), "-version"])
        self.assertEqual(self.run.call_args_list[4].args[0], [str(ffprobe), "-version"])
        self.assertNotIn(mock.call("ffmpeg"), self.which.call_args_list)

    def test_configured_ffmpeg_takes_precedence_over_sibling(self):
        ffprobe = self.root / ("ffprobe.exe" if os.name == "nt" else "ffprobe")
        sibling = self.root / ("ffmpeg.exe" if os.name == "nt" else "ffmpeg")
        configured = self.root / ("configured-ffmpeg.exe" if os.name == "nt" else "configured-ffmpeg")
        for path in (ffprobe, sibling, configured):
            path.write_bytes(b"inert fixture; never executed")
        self.environment.update(LIBRA_FFPROBE_PATH=str(ffprobe), LIBRA_FFMPEG_PATH=str(configured))
        record(self.root)
        self.assertEqual(self.run.call_args_list[3].args[0], [str(configured), "-version"])

    def test_missing_ffmpeg_fails(self):
        self.which.side_effect = lambda name: None if name == "ffmpeg" else name
        with self.assertRaisesRegex(ValueError, "Missing tool on PATH: ffmpeg"):
            record(self.root)
        self.run.assert_not_called()

    def test_java_home_must_contain_java_file(self):
        for home in ("", str(self.root / "missing"), str(self.root)):
            with self.subTest(home=home), mock.patch.dict(os.environ, {"JAVA_HOME": home}):
                with self.assertRaisesRegex(ValueError, "Configured tool is not an existing file: java"):
                    record(self.root)
        self.run.assert_not_called()

    def test_java_home_takes_precedence_over_path(self):
        java = self.root / "jdk/bin" / ("java.exe" if os.name == "nt" else "java")
        java.parent.mkdir(parents=True)
        java.write_bytes(b"inert executable fixture; never executed")
        with mock.patch.dict(os.environ, {"JAVA_HOME": str(self.root / "jdk")}):
            record(self.root)
        self.assertEqual(self.run.call_args_list[0].args[0], [str(java), "-version"])
        self.assertNotIn(mock.call("java"), self.which.call_args_list)

    def test_picked_up_options_are_filtered_before_first_version(self):
        self.run.side_effect = None
        self.run.return_value = subprocess.CompletedProcess([], 0, "", "\n".join((
            "Picked up JAVA_TOOL_OPTIONS: -Dfixture.version=discard",
            "Picked up _JAVA_OPTIONS: -Dfixture.version=discard",
            "Picked up JDK_JAVA_OPTIONS: -Dfixture.version=discard",
            "unrelated startup notice", 'openjdk version "21"', "later version ignored")))
        self.assertEqual(version(["java", "-version"], "java"), 'openjdk version "21"')

    def test_prefixed_jdk_options_are_filtered(self):
        self.run.side_effect = None
        self.run.return_value = subprocess.CompletedProcess([], 0, "", "\n".join((
            "NOTE: Picked up JDK_JAVA_OPTIONS: -Dx.version=1", 'openjdk version "21"')))
        self.assertEqual(version(["java", "-version"], "java"), 'openjdk version "21"')
        self.assertEqual(version(["docker", "--version"], "docker"), 'openjdk version "21"')
        self.run.return_value = subprocess.CompletedProcess(
            [], 0, "NOTE: Picked up JDK_JAVA_OPTIONS: -Dx.version=1", "")
        with self.assertRaisesRegex(ValueError, "Missing version string: java"):
            version(["java", "-version"], "java")

    def test_java_skips_nonmatching_version_but_docker_uses_first(self):
        self.run.side_effect = None
        self.run.return_value = subprocess.CompletedProcess(
            [], 0, 'startup version 1\n  openjdk version "21"\nlater version 2', "")
        self.assertEqual(version(["java", "-version"], "java"), 'openjdk version "21"')
        self.assertEqual(version(["docker", "--version"], "docker"), "startup version 1")

    def test_missing_version_line(self):
        self.run.side_effect = None
        for output in ("", "startup notice", "Picked up JAVA_TOOL_OPTIONS: -Dfixture.version=discard"):
            with self.subTest(output=output):
                self.run.return_value = subprocess.CompletedProcess([], 0, output, "")
                with self.assertRaisesRegex(ValueError, "Missing version string: java"):
                    version(["java", "-version"], "java")

    def test_distribution_query_and_fragment_are_removed(self):
        self.properties.write_text(
            "wrapperVersion=3.3.4\ndistributionUrl=https://example-user:example-placeholder@"
            "example.test:8443/maven.zip?fixture=query-value#fixture-fragment\n", encoding="utf-8")
        record(self.root)
        result = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertEqual(result["distributionUrl"], "https://example.test:8443/maven.zip")
        self.assertEqual(list(result)[:5], ["java", "docker", "dockerCompose", "ffmpeg", "ffprobe"])

    def test_distribution_userinfo_is_removed_and_shape_preserved(self):
        # Inert URL userinfo, never an actual credential or a contacted endpoint.
        self.properties.write_text(
            "wrapperVersion=3.3.4\ndistributionUrl=https://example-user:example-placeholder@example.test:8443/maven.zip\n",
            encoding="utf-8")
        record(self.root)
        result = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertEqual(result["distributionUrl"], "https://example.test:8443/maven.zip")
        self.assertEqual(set(result), {"java", "docker", "dockerCompose", "ffmpeg", "ffprobe", "python",
                                       "wrapperVersion", "distributionUrl", "revision"})
        self.assertEqual(result["revision"], self.revision)

    def test_revision_cli_writes_only_compact_json(self):
        with redirect_stdout(io.StringIO()) as output, mock.patch("record_toolchain.record") as recorder:
            main(["--root", str(self.root), "--revision"])
        self.assertEqual(output.getvalue(), json.dumps(self.revision, separators=(",", ":")) + "\n")
        recorder.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_revision_failure_removes_previous_evidence(self):
        self.output.parent.mkdir(parents=True)
        self.output.write_text('{}', encoding="utf-8")
        with mock.patch("record_toolchain.revision_record", side_effect=ValueError("Git unavailable")):
            with self.assertRaisesRegex(ValueError, "Git unavailable"):
                record(self.root)
        self.assertFalse(self.output.exists())


class RevisionRecordTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.test")
        self.git("config", "core.autocrlf", "false")
        (self.root / ".gitignore").write_bytes(b"ignored/\n")
        (self.root / "tracked").write_bytes(b"initial\n")
        self.git("add", ".")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "fixture")

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.root), *arguments], capture_output=True, check=True).stdout

    def test_clean_revision(self):
        revision = revision_record(self.root)
        self.assertEqual(revision, {"commit": self.git("rev-parse", "HEAD").decode().strip(), "dirty": False,
                                    "changedPaths": 0, "stateSha256": hashlib.sha256(b"").hexdigest()})

    def test_tracked_staged_and_unstaged_binary_changes(self):
        clean = revision_record(self.root)
        (self.root / "tracked").write_bytes(b"binary\0fixture\n")
        dirty = revision_record(self.root)
        self.assertTrue(dirty["dirty"])
        self.assertEqual(dirty["changedPaths"], 1)
        self.assertNotEqual(dirty["stateSha256"], clean["stateSha256"])
        self.git("add", "tracked")
        self.assertEqual(revision_record(self.root), dirty)
        (self.root / "tracked").write_bytes(b"binary\0changed\n")
        self.assertNotEqual(revision_record(self.root)["stateSha256"], dirty["stateSha256"])

    def test_untracked_paths_contents_and_sorted_hashing(self):
        for name in ("z fixture", "a fixture"):
            (self.root / name).write_bytes(b"inert\0fixture")
        revision = revision_record(self.root)
        state = hashlib.sha256(b"")
        for name in ("a fixture", "z fixture"):
            state.update(b"\0" + name.encode() + b"\0" + self.git("hash-object", "--", name).strip() + b"\0")
        self.assertEqual(revision["stateSha256"], state.hexdigest())
        self.assertEqual(revision["changedPaths"], 2)
        (self.root / "a fixture").write_bytes(b"changed")
        self.assertNotEqual(revision_record(self.root)["stateSha256"], revision["stateSha256"])
        (self.root / "a fixture").write_bytes(b"inert\0fixture")
        (self.root / "a fixture").rename(self.root / "b fixture")
        self.assertNotEqual(revision_record(self.root)["stateSha256"], revision["stateSha256"])

    def test_ignored_files_do_not_change_revision(self):
        clean = revision_record(self.root)
        (self.root / "ignored").mkdir()
        (self.root / "ignored/fixture").write_bytes(b"ignored")
        self.assertEqual(revision_record(self.root), clean)

    def test_git_failures_fail_closed(self):
        for command in ("rev-parse", "status", "diff", "ls-files", "hash-object"):
            (self.root / "untracked").write_bytes(b"inert")
            original = subprocess.run

            def run(arguments, **kwargs):
                if arguments[3] == command:
                    return subprocess.CompletedProcess(arguments, 1, b"", b"inert failure")
                return original(arguments, **kwargs)

            with self.subTest(command=command), mock.patch("record_toolchain.subprocess.run", side_effect=run):
                with self.assertRaisesRegex(ValueError, "Unable to read Git revision"):
                    revision_record(self.root)

    def test_git_oserror_fails_closed(self):
        with mock.patch("record_toolchain.subprocess.run", side_effect=OSError):
            with self.assertRaisesRegex(ValueError, "Unable to read Git revision"):
                revision_record(self.root)

    def test_malformed_commit_fails_closed(self):
        for commit in (b"A" * 40, b"a" * 39, b"not-a-commit"):
            with self.subTest(commit=commit), mock.patch("record_toolchain.subprocess.run", return_value=
                    subprocess.CompletedProcess([], 0, commit + b"\n", b"")):
                with self.assertRaisesRegex(ValueError, "Malformed Git commit"):
                    revision_record(self.root)


if __name__ == "__main__":
    unittest.main()
