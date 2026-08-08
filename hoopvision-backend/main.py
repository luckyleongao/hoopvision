from fastapi import FastAPI
from pydantic import BaseModel, Field
from typing import List
from services.dtw_service import analyze_shot_data

app = FastAPI(title="HoopVision Backend")

class SmoothedLandmark(BaseModel):
    x: float
    y: float
    z: float
    visibility: float

class ShotFrame(BaseModel):
    timestamp_ms: int
    landmarks: List[SmoothedLandmark]

class ShotAnalysisRequest(BaseModel):
    template_id: str = "standard_shot"
    frames: List[ShotFrame]
    keyframe_images_base64: List[str] = Field(default_factory=list)

@app.post("/api/v1/analyze_shot")
def analyze_shot(request: ShotAnalysisRequest):
    analysis_result = analyze_shot_data(
        request.template_id,
        request.frames,
        request.keyframe_images_base64
    )
    return analysis_result

@app.get("/")
def root():
    return {"message": "HoopVision AI Coach Backend is running!", "status": "ok"}

@app.get("/health")
def health_check():
    return {"status": "ok"}

