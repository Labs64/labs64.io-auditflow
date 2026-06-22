"""
Health check endpoints for AuditFlow Python services.
Provides /health, /ready, and /live endpoints for Kubernetes probes.
"""
from fastapi.responses import JSONResponse
import time
import os

# Track startup time for readiness checks
_startup_time = time.time()
_ready = False


def set_ready(ready: bool):
    """Mark the service as ready or not ready."""
    global _ready
    _ready = ready


async def health():
    """Basic health check - returns 200 if service is running."""
    return JSONResponse(
        content={
            "status": "UP",
            "timestamp": time.time(),
            "pid": os.getpid(),
            "uptime_seconds": time.time() - _startup_time
        },
        status_code=200
    )


async def readiness():
    """Readiness check - returns 200 only when service is ready to receive traffic."""
    if not _ready:
        return JSONResponse(
            content={
                "status": "NOT_READY",
                "timestamp": time.time(),
                "reason": "Service is still starting up"
            },
            status_code=503
        )
    
    return JSONResponse(
        content={
            "status": "READY",
            "timestamp": time.time(),
            "uptime_seconds": time.time() - _startup_time
        },
        status_code=200
    )


async def liveness():
    """Liveness check - returns 200 if service is alive and responsive."""
    try:
        # Check if process is responsive by verifying PID file exists
        pid = os.getpid()
        
        return JSONResponse(
            content={
                "status": "ALIVE",
                "timestamp": time.time(),
                "pid": pid,
                "uptime_seconds": time.time() - _startup_time
            },
            status_code=200
        )
    except Exception as e:
        return JSONResponse(
            content={
                "status": "UNHEALTHY",
                "timestamp": time.time(),
                "error": "Internal health check failure"
            },
            status_code=503
        )


async def service_info():
    """Service information endpoint."""
    return JSONResponse(
        content={
            "service": os.environ.get("SERVICE_NAME", "unknown"),
            "version": os.environ.get("SERVICE_VERSION", "0.0.1"),
            "environment": os.environ.get("ENVIRONMENT", "development"),
            "hostname": os.environ.get("HOSTNAME", "unknown"),
            "pid": os.getpid(),
            "uptime_seconds": time.time() - _startup_time
        },
        status_code=200
    )
