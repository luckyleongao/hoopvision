import numpy as np
from fastdtw import fastdtw
import math
from services.llm_service import generate_coach_feedback

def custom_euclidean(x, y):
    return abs(x - y)

def calculate_angle(p1, p2, p3):
    radians = math.atan2(p3.y - p2.y, p3.x - p2.x) - math.atan2(p1.y - p2.y, p1.x - p2.x)
    angle = abs(math.degrees(radians))
    if angle > 180.0:
        angle = 360.0 - angle
    return angle

def analyze_shot_data(template_id, frames, keyframe_images=None):
    if not frames:
        return {"error": "No frames provided"}
        
    elbow_angles = []
    knee_angles = []
    
    for frame in frames:
        landmarks = frame.landmarks
        if len(landmarks) >= 29:
            # Right side
            shoulder = landmarks[12]
            elbow = landmarks[14]
            wrist = landmarks[16]
            elbow_angles.append(calculate_angle(shoulder, elbow, wrist))
            
            hip = landmarks[24]
            knee = landmarks[26]
            ankle = landmarks[28]
            knee_angles.append(calculate_angle(hip, knee, ankle))

    if not elbow_angles:
        return {"error": "No valid landmarks found"}

    # --- Star Archetypes Reference Dynamic Curves ---
    if template_id == "curry":
        # Curry: One-Motion fast push, continuous momentum without pause, set-point ~98°
        template_elbow = np.linspace(160, 98, 8).tolist() + np.linspace(98, 175, 12).tolist()
    elif template_id == "kobe":
        # Kobe: Two-Motion high jump-shot, deep set-point ~80°, brief plateau at apex
        template_elbow = np.linspace(170, 80, 8).tolist() + [80, 80, 82] + np.linspace(82, 180, 9).tolist()
    elif template_id == "klay":
        # Klay: Pure textbook right-angle L-shape (90°), symmetric vertical mechanics
        template_elbow = np.linspace(170, 90, 10).tolist() + np.linspace(90, 175, 10).tolist()
    else:
        # Standard default
        template_elbow = np.linspace(170, 90, 10).tolist() + np.linspace(90, 175, 10).tolist()
    
    user_elbow = np.array(elbow_angles)
    template_elbow_np = np.array(template_elbow)
    
    # Run DTW algorithm to compare time-series curves regardless of speed
    distance, path = fastdtw(user_elbow, template_elbow_np, dist=custom_euclidean)
    
    min_elbow = min(elbow_angles)
    min_knee = min(knee_angles) if knee_angles else 180
    
    # Generate dynamic multimodal feedback using Gemini LLM
    feedback = generate_coach_feedback(
        dtw_distance=float(distance),
        min_elbow_angle=float(min_elbow),
        min_knee_angle=float(min_knee),
        template_id=template_id,
        keyframe_images=keyframe_images or []
    )
        
    return {
        "status": "success",
        "dtw_distance": float(distance),
        "min_elbow_angle": float(min_elbow),
        "min_knee_angle": float(min_knee),
        "feedback": feedback
    }
