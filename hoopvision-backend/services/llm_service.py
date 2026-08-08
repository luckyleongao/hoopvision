import os
import requests
import json

# Read API key from environment variable
api_key = os.environ.get("GEMINI_API_KEY")
if api_key:
    api_key = api_key.strip()

def generate_coach_feedback(
    dtw_distance: float, 
    min_elbow_angle: float, 
    min_knee_angle: float, 
    template_id: str,
    keyframe_images: list = None
) -> str:
    if not api_key:
        return f"【本地规则反馈】手肘{int(min_elbow_angle)}°，膝盖{int(min_knee_angle)}°。(提示: 请在终端 export GEMINI_API_KEY)"
        
    has_images = bool(keyframe_images and len(keyframe_images) > 0)
    
    star_profiles = {
        "curry": {
            "name": "斯蒂芬·库里 (Stephen Curry)",
            "style": "One-Motion 极致连贯推射流",
            "focus": "重点检查【下蹲蓄力与向上举球是否同步进行】、【起跳未至最高点即借力极速推射出手】。无需深蹲，重点是动量由下至上零停顿传递！"
        },
        "kobe": {
            "name": "科比·布莱恩特 (Kobe Bryant)",
            "style": "Two-Motion 滞空干拔跳投",
            "focus": "重点检查【深蹲起跳至最高点滞空瞬间才伸臂出手】、【手肘高位架起形成高出手点】、【核心收紧与滞空稳定性】。"
        },
        "klay": {
            "name": "克莱·汤普森 (Klay Thompson)",
            "style": "教科书式标准定点投篮",
            "focus": "重点检查【托球手肘是否严格呈现 90 度 L 型标准直角】、【起跳垂直起落中轴线不歪斜】、【辅助手干净不发力】。"
        }
    }
    
    star_info = star_profiles.get(template_id, {
        "name": "NBA 顶级球星",
        "style": "标准跳投",
        "focus": "重点检查托球手型、下肢发力与身体垂直度。"
    })

    if has_images:
        prompt = f"""
你现在是 {star_info['name']} 的专属私人投篮教练。
球员正在专项模仿 {star_info['name']} 的【{star_info['style']}】！
这里附带了球员本次投篮过程中的 {len(keyframe_images)} 张关键瞬间实拍照片（依次为：1. 下蹲蓄力、2. 举球托球、3. 顶峰出手）。
同时测得骨骼动力学数据：
1. 动作与{star_info['name']}的节奏匹配偏差值 (DTW): {int(dtw_distance)} (数值越小越好，< 450 算极佳)。
2. 举球时手肘最小夹角: {int(min_elbow_angle)} 度。
3. 蓄力时膝盖最小弯曲角度: {int(min_knee_angle)} 度。

【针对性教学重点】：
{star_info['focus']}

【任务指令】：
请仔细观察 3 张实拍照片中的身体姿态与手型，结合骨骼数据：
用极具针对性、热血且专业的教练口吻（严格控制在 55 字以内），给出围绕【{star_info['name']} 技术流派】的一句一针见血的指导或鼓励！不要解释数字，直接发号施令！
"""
    else:
        prompt = f"""
你现在是 {star_info['name']} 的专属私人投篮教练。
球员正在专项模仿 {star_info['name']} 的【{star_info['style']}】！
以下是动作与标准模板的对比数据：
1. 节奏偏差值 (DTW): {int(dtw_distance)}。
2. 举球手肘夹角: {int(min_elbow_angle)} 度。
3. 蓄力膝盖角度: {int(min_knee_angle)} 度。

教学重点：{star_info['focus']}
请用极短、一针见血的教练口吻（45 字以内）给出指令！
"""
    
    try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={api_key}"
        headers = {"Content-Type": "application/json"}
        
        parts = [{"text": prompt}]
        
        if has_images:
            for img_b64 in keyframe_images:
                clean_data = img_b64.split(",")[-1].strip()
                if clean_data:
                    parts.append({
                        "inlineData": {
                            "mimeType": "image/jpeg",
                            "data": clean_data
                        }
                    })
        
        payload = {
            "contents": [{
                "parts": parts
            }]
        }
        
        response = requests.post(url, headers=headers, json=payload, timeout=15)
        
        if response.status_code == 200:
            data = response.json()
            # Parse the text from Gemini response
            feedback_text = data.get("candidates", [{}])[0].get("content", {}).get("parts", [{}])[0].get("text", "")
            if feedback_text:
                multimodal_tag = "【AI 多模态视觉教练】" if has_images else "【AI 教练】"
                return f"{multimodal_tag}{feedback_text.strip()}"
            else:
                return "【AI 教练】分析完成，但无文本返回。"
        else:
            return f"【AI 诊断异常】状态码 {response.status_code}, 详情: {response.text[:100]}"
            
    except Exception as e:
        return f"【AI 诊断异常】网络请求失败 ({str(e)})"
