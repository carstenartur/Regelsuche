"""Regelsuche's dependency-free client and independent rational solution checker."""
from .client import Client, ClientError, LinearSolution
from .verify import VerificationError, verify_artifact, verify_study

__version__ = "0.1.0"
__all__ = ["Client", "ClientError", "LinearSolution", "VerificationError", "verify_artifact", "verify_study"]
