"""Exercise the real opt-in classpath task against an isolated Java build."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]


class ClasspathContractTest(unittest.TestCase):
    def test_changed_dependency_path_regenerates_the_launch_classpath(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'settings.gradle').write_text("include 'regelsuche-learning'\n", encoding='utf-8')
            learning = root/'regelsuche-learning'
            learning.mkdir()
            (learning/'build.gradle').write_text("""plugins { id 'java' }
dependencies {
    runtimeOnly files(rootProject.file('dependency-path.txt').text.trim())
}
""", encoding='utf-8')
            first, second = root/'first.jar', root/'second.jar'
            with zipfile.ZipFile(first, 'w'):
                pass
            shutil.copyfile(first, second)
            dependency = root/'dependency-path.txt'
            dependency.write_text(str(first), encoding='utf-8')
            output = learning/'build/external-polynomial-classpath.txt'

            def build():
                completed = subprocess.run([
                    os.environ.get('REGELSUCHE_TEST_GRADLE', str(ROOT/'gradlew')),
                    '--no-daemon', '--no-configuration-cache', '--max-workers=2', '--console=plain',
                    '-p', str(root), '-I', str(ROOT/'scripts/external-polynomial-classpath.gradle'),
                    ':regelsuche-learning:externalPolynomialClasspath'],
                    capture_output=True, text=True, timeout=180)
                self.assertEqual(0, completed.returncode, completed.stdout+completed.stderr)
                return output.read_text(encoding='utf-8').split(os.pathsep)

            self.assertIn(str(first), build())
            self.assertIn(str(first), build())
            # Identical bytes at a different path must still regenerate the
            # literal launch classpath; normalized content alone is insufficient.
            dependency.write_text(str(second), encoding='utf-8')
            updated = build()
            self.assertIn(str(second), updated)
            self.assertNotIn(str(first), updated)


if __name__ == '__main__':
    unittest.main()
