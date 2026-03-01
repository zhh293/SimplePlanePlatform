from PIL import Image
import img2pdf
import os
import sys


def images_to_pdf(image_paths, output_pdf):
    """
    将图片转换为PDF文件
    
    Args:
        image_paths: 图片路径列表
        output_pdf: 输出的PDF文件路径
    """
    try:
        with open(output_pdf, "wb") as f:
            f.write(img2pdf.convert(image_paths))
        print(f"成功生成PDF文件: {output_pdf}")
        return True
    except Exception as e:
        print(f"转换失败: {e}")
        return False


def get_images_from_folder(folder_path):
    """
    从文件夹中获取所有图片文件
    
    Args:
        folder_path: 文件夹路径
        
    Returns:
        图片路径列表
    """
    image_extensions = {'.jpg', '.jpeg', '.png', '.bmp', '.gif', '.tiff', '.webp'}
    image_paths = []
    
    for filename in os.listdir(folder_path):
        if os.path.splitext(filename)[1].lower() in image_extensions:
            image_paths.append(os.path.join(folder_path, filename))
    
    return sorted(image_paths)


def main():
    if len(sys.argv) < 2:
        print("使用方法:")
        print("  1. 转换单个或多个图片:")
        print("     python image_to_pdf.py 图片1.jpg 图片2.png 输出.pdf")
        print("  2. 转换整个文件夹的图片:")
        print("     python image_to_pdf.py --folder 文件夹路径 输出.pdf")
        return
    
    output_pdf = sys.argv[-1]
    
    if sys.argv[1] == "--folder":
        if len(sys.argv) < 4:
            print("错误: 请指定文件夹路径和输出文件名")
            return
        
        folder_path = sys.argv[2]
        output_pdf = sys.argv[3]
        
        if not os.path.isdir(folder_path):
            print(f"错误: 文件夹不存在: {folder_path}")
            return
        
        image_paths = get_images_from_folder(folder_path)
        
        if not image_paths:
            print(f"错误: 文件夹中没有找到图片文件: {folder_path}")
            return
        
        print(f"找到 {len(image_paths)} 张图片:")
        for path in image_paths:
            print(f"  - {os.path.basename(path)}")
    else:
        image_paths = sys.argv[1:-1]
        
        for path in image_paths:
            if not os.path.isfile(path):
                print(f"错误: 文件不存在: {path}")
                return
    
    images_to_pdf(image_paths, output_pdf)


if __name__ == "__main__":
    main()
