"""Bound and normalize the image parts supplied by the native attachment picker."""
import base64
import binascii


MAX_ENCODED_IMAGE = 512 * 1024
MAX_IMAGES = 3


def input_images(request):
    images = request.get('images', [])
    if not isinstance(images, list) or len(images) > MAX_IMAGES:
        raise ValueError('每条消息最多附带 3 张图片')
    if images and (request.get('compactOnly') or request.get('isSubagent') or
                   request.get('engineId') == 'openclaude' or
                   (request.get('engineId') == 'pi' and (request.get('configuration') or {}).get('freeMode'))):
        raise ValueError('当前模型通道不支持图片附件')
    parts = []
    for image in images:
        if not isinstance(image, dict) or image.get('mimeType') != 'image/jpeg':
            raise ValueError('图片附件格式无效')
        data = image.get('data')
        if not isinstance(data, str) or not data or len(data) > MAX_ENCODED_IMAGE:
            raise ValueError('图片附件超过发送限制')
        try:
            raw = base64.b64decode(data, validate=True)
        except (ValueError, binascii.Error):
            raise ValueError('图片附件编码无效') from None
        if not raw.startswith(b'\xff\xd8\xff') or not raw.endswith(b'\xff\xd9'):
            raise ValueError('图片附件不是有效的 JPEG')
        parts.append({'mimeType': 'image/jpeg', 'data': data})
    return parts
