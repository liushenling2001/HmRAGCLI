from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.schemas.common import PageResult
from app.schemas.operations import (
    OperationsDashboardOut,
    OperationsFailureFileOut,
    OperationsJobDeleteOut,
    OperationsJobOut,
)
from app.services import operations as service

router = APIRouter()


@router.get("/dashboard", response_model=OperationsDashboardOut)
def get_operations_dashboard(db: Session = Depends(get_db)) -> OperationsDashboardOut:
    return service.get_operations_dashboard(db)


@router.get("/jobs", response_model=PageResult[OperationsJobOut])
def list_operations_jobs(
    job_kind: str | None = None,
    status: str | None = None,
    data_source_id: str | None = None,
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db),
) -> PageResult[OperationsJobOut]:
    items, total = service.list_recent_operations_jobs(
        db,
        job_kind=job_kind,
        status=status,
        data_source_id=data_source_id,
        page=page,
        page_size=page_size,
    )
    return PageResult(items=items, total=total, page=page, page_size=page_size)


@router.get("/failures", response_model=PageResult[OperationsFailureFileOut])
def list_operations_failures(
    data_source_id: str | None = None,
    file_ext: str | None = None,
    parse_status: str | None = None,
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db),
) -> PageResult[OperationsFailureFileOut]:
    items, total = service.list_recent_failure_files(
        db,
        data_source_id=data_source_id,
        file_ext=file_ext,
        parse_status=parse_status,
        page=page,
        page_size=page_size,
    )
    return PageResult(items=items, total=total, page=page, page_size=page_size)


@router.delete("/jobs/{job_kind}/{job_id}", response_model=OperationsJobDeleteOut)
def delete_operations_job(
    job_kind: str,
    job_id: str,
    db: Session = Depends(get_db),
) -> OperationsJobDeleteOut:
    deleted_kind, deleted_id = service.delete_operations_job(db, job_kind=job_kind, job_id=job_id)
    return OperationsJobDeleteOut(id=deleted_id, job_kind=deleted_kind, deleted=True)
