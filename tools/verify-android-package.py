"""Run the current release package audit (kept as a stable developer entry point)."""
from pathlib import Path
import runpy
root=Path(__file__).resolve().parents[1]
runpy.run_path(str(root/'tests/audit_apk.py'),run_name='__main__')
runpy.run_path(str(root/'tests/native/package_report.py'),run_name='__main__')
