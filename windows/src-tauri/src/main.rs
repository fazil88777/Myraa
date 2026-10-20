// MYRA for Windows - Rust backend
// Laptop commands jo Gemini Live ke function calls se chalte hain.
// Prevents additional console window on Windows in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

fn desktop() -> PathBuf {
    let home = std::env::var("USERPROFILE").unwrap_or_else(|_| "C:\\Users\\Public".to_string());
    Path::new(&home).join("Desktop")
}

fn resolve_on_desktop(name: &str) -> PathBuf {
    let p = Path::new(name.trim());
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        desktop().join(p)
    }
}

fn chrome_exe() -> Option<PathBuf> {
    let pf = std::env::var("ProgramFiles").unwrap_or_default();
    let pfx = std::env::var("ProgramFiles(x86)").unwrap_or_default();
    let lad = std::env::var("LOCALAPPDATA").unwrap_or_default();
    let cands = [
        Path::new(&pf).join("Google\\Chrome\\Application\\chrome.exe"),
        Path::new(&pfx).join("Google\\Chrome\\Application\\chrome.exe"),
        Path::new(&lad).join("Google\\Chrome\\Application\\chrome.exe"),
    ];
    cands.into_iter().find(|p| p.exists())
}

#[tauri::command]
fn open_chrome(url: Option<String>) -> Result<String, String> {
    let target = url.unwrap_or_else(|| "https://www.google.com".to_string());
    if let Some(exe) = chrome_exe() {
        Command::new(&exe)
            .arg(&target)
            .spawn()
            .map_err(|e| e.to_string())?;
        return Ok(format!("Chrome mein khol diya: {}", target));
    }
    Command::new("cmd")
        .args(["/C", "start", "", &target])
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(format!("Default browser mein khol diya: {}", target))
}

#[tauri::command]
fn create_folder(name: String) -> Result<String, String> {
    let path = resolve_on_desktop(&name);
    if path.exists() {
        return Ok(format!("Ye folder pehle se mojood hai: {}", path.display()));
    }
    fs::create_dir_all(&path).map_err(|e| e.to_string())?;
    Ok(format!("Folder ban gaya Desktop par: {}", name.trim()))
}

#[tauri::command]
fn create_file(name: String) -> Result<String, String> {
    let mut n = name.trim().to_string();
    if !n.contains('.') {
        n.push_str(".txt");
    }
    let path = resolve_on_desktop(&n);
    if path.exists() {
        return Ok(format!("Ye file pehle se mojood hai: {}", path.display()));
    }
    fs::write(&path, "").map_err(|e| e.to_string())?;
    Ok(format!("File ban gayi Desktop par: {}", n))
}

fn copy_recursive(src: &Path, dst: &Path) -> Result<(), String> {
    if src.is_dir() {
        fs::create_dir_all(dst).map_err(|e| e.to_string())?;
        for entry in fs::read_dir(src).map_err(|e| e.to_string())? {
            let entry = entry.map_err(|e| e.to_string())?;
            copy_recursive(&entry.path(), &dst.join(entry.file_name()))?;
        }
    } else {
        if let Some(parent) = dst.parent() {
            let _ = fs::create_dir_all(parent);
        }
        fs::copy(src, dst).map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
fn move_item(source: String, destination: String) -> Result<String, String> {
    let src = resolve_on_desktop(&source);
    let mut dst = resolve_on_desktop(&destination);
    if !src.exists() {
        return Err(format!("Ye nahi mila Desktop par: {}", source.trim()));
    }
    if dst.is_dir() {
        dst = dst.join(src.file_name().ok_or("bad source name")?);
    }
    if fs::rename(&src, &dst).is_err() {
        copy_recursive(&src, &dst)?;
        if src.is_dir() {
            fs::remove_dir_all(&src).map_err(|e| e.to_string())?;
        } else {
            fs::remove_file(&src).map_err(|e| e.to_string())?;
        }
    }
    Ok(format!("Move ho gaya: {} -> {}", source.trim(), destination.trim()))
}

#[tauri::command]
fn copy_item(source: String, destination: String) -> Result<String, String> {
    let src = resolve_on_desktop(&source);
    let mut dst = resolve_on_desktop(&destination);
    if !src.exists() {
        return Err(format!("Ye nahi mila Desktop par: {}", source.trim()));
    }
    if dst.is_dir() {
        dst = dst.join(src.file_name().ok_or("bad source name")?);
    }
    copy_recursive(&src, &dst)?;
    Ok(format!("Copy ho gaya: {} -> {}", source.trim(), destination.trim()))
}

#[tauri::command]
fn open_app(app: String) -> Result<String, String> {
    let exe = match app.to_lowercase().as_str() {
        "notepad" => "notepad.exe",
        "calculator" | "calc" => "calc.exe",
        "paint" => "mspaint.exe",
        "explorer" | "file explorer" => "explorer.exe",
        "cmd" => "cmd.exe",
        _ => return Err(format!("Ye app list mein nahi hai: {}", app)),
    };
    Command::new(exe).spawn().map_err(|e| e.to_string())?;
    Ok(format!("App khol di: {}", app))
}

#[tauri::command]
fn set_volume(action: String) -> Result<String, String> {
    let (code, reps) = match action.as_str() {
        "up" => (175, 5),
        "down" => (174, 5),
        "mute" => (173, 1),
        _ => return Err(format!("Volume action samajh nahi aaya: {}", action)),
    };
    let script = format!(
        "$w=New-Object -ComObject WScript.Shell; 1..{} | ForEach-Object {{ $w.SendKeys([char]{}) }}",
        reps, code
    );
    Command::new("powershell")
        .args(["-NoProfile", "-Command", &script])
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(format!("Volume {}", action))
}

#[tauri::command]
fn take_screenshot() -> Result<String, String> {
    let dir = desktop().join("Screenshots");
    let dir_str = dir.to_string_lossy().replace('\'', "''");
    let script = format!(
        "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; \
         $b=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds; \
         $bmp=New-Object System.Drawing.Bitmap($b.Width,$b.Height); \
         $g=[System.Drawing.Graphics]::FromImage($bmp); \
         $g.CopyFromScreen($b.Location,[System.Drawing.Point]::Empty,$b.Size); \
         $dir='{}'; New-Item -ItemType Directory -Force -Path $dir | Out-Null; \
         $p=Join-Path $dir ('shot_'+(Get-Date -Format 'yyyyMMdd_HHmmss')+'.png'); \
         $bmp.Save($p); Write-Output $p",
        dir_str
    );
    let out = Command::new("powershell")
        .args(["-NoProfile", "-Command", &script])
        .output()
        .map_err(|e| e.to_string())?;
    if out.status.success() {
        let p = String::from_utf8_lossy(&out.stdout).trim().to_string();
        Ok(format!("Screenshot le liya, Desktop/Screenshots mein hai: {}", p))
    } else {
        Err("Screenshot nahi ho saka".to_string())
    }
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            open_chrome,
            create_folder,
            create_file,
            move_item,
            copy_item,
            open_app,
            set_volume,
            take_screenshot
        ])
        .run(tauri::generate_context!())
        .expect("error while running MYRA");
}
