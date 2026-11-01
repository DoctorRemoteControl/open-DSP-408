"""Exercise the stress launcher's real process ownership boundary with harmless children."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest


def running(pid):
    try:
        return Path(f'/proc/{pid}/stat').read_text().split()[2] != 'Z'
    except FileNotFoundError:
        return False


@unittest.skipUnless(os.name == 'posix' and Path('/proc').is_dir(), 'Linux process ownership test')
class StressCleanupTest(unittest.TestCase):
    def test_termination_reaps_the_owned_java_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root/'controller'
            scripts = source/'integration'
            scripts.mkdir(parents=True)
            shutil.copy2(Path(__file__).with_name('connection_stress.py'), scripts/'connection_stress.py')
            inputs = [source/'dsp408-parent/dsp408-core/target/dsp408-core-0.0.1-SNAPSHOT.jar',
                      root/'protocol/target/dsp408-protocol-0.1.0-SNAPSHOT.jar']
            for name in ['jackson-annotations', 'jackson-core', 'jackson-databind']:
                inputs.append(root/'m2/com/fasterxml/jackson/core'/name/'2.17.2'/f'{name}-2.17.2.jar')
            for path in inputs:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'process-boundary fixture, not a Java build')
            tools = root/'tools'
            tools.mkdir()
            child_file = root/'child.pid'
            for name, body in [('javac', 'raise SystemExit(0)'),
                               ('java', 'import os,time\nfrom pathlib import Path\n'
                                'Path(os.environ["TEST_CHILD_PID"]).write_text(str(os.getpid()))\ntime.sleep(60)')]:
                executable = tools/name
                executable.write_text(f'#!{sys.executable}\n{body}\n')
                executable.chmod(0o755)
            environment = os.environ | {'PATH': str(tools)+os.pathsep+os.environ['PATH'],
                                         'TEST_CHILD_PID': str(child_file),
                                         'DSP408_PROTOCOL_DIR': str(root/'protocol')}
            process = subprocess.Popen([sys.executable, str(scripts/'connection_stress.py'),
                                        '--cycles', '1', '--maven-repo', str(root/'m2'),
                                        '--state-dir', str(root/'state')], env=environment,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            child_pid = None
            try:
                deadline = time.monotonic()+5
                while not child_file.exists() and process.poll() is None and time.monotonic()<deadline:
                    time.sleep(.01)
                self.assertTrue(child_file.exists(), 'Launcher must reach the real child boundary')
                child_pid = int(child_file.read_text())
                process.terminate()
                process.wait(timeout=5)
                self.assertFalse(running(child_pid), 'Stopping the launcher must not leave its Java child alive')
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
                if child_pid and running(child_pid):
                    os.kill(child_pid, signal.SIGKILL)
                process.stdout.close()
                process.stderr.close()

if __name__ == '__main__':
    unittest.main()
