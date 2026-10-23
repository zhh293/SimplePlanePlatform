#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod commands;
mod config;
mod dns;
mod logs;
mod process;
mod proxy;
mod state;

use state::AppState;
use std::sync::Arc;
use tauri::Manager;
use tokio::sync::Mutex;

fn main() {
    env_logger::init();

    let app_state = Arc::new(Mutex::new(AppState::default()));

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(app_state)
        .invoke_handler(tauri::generate_handler![
            commands::connect,
            commands::disconnect,
            commands::status,
            commands::get_config,
            commands::import_node_certificate,
            commands::save_config,
            commands::get_tun_config,
            commands::save_tun_config,
            commands::get_tun_config_raw,
            commands::save_tun_config_raw,
            commands::get_route_config,
            commands::save_route_config,
            commands::get_presets,
            commands::save_preset,
            commands::delete_preset,
            commands::apply_preset,
            commands::import_servers,
            commands::reset_network,
            commands::get_logs,
            commands::get_logs_stream,
            commands::clear_logs,
            commands::get_config_dir,
            commands::diagnose_tun,
            commands::check_update_via_bootstrap,
            commands::install_update,
            commands::get_update_config,
            commands::set_update_config,
        ])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                let state = window.state::<Arc<Mutex<AppState>>>();
                let state_clone = state.inner().clone();
                tauri::async_runtime::spawn(async move {
                    let mut s = state_clone.lock().await;
                    s.cleanup().await;
                });
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
