use iroh_blobs::api::TempTag;
use iroh_blobs::store::mem::MemStore;
use n0_future::task::AbortOnDropHandle;

pub struct WasmShareSession {
    pub ticket: String,
    pub hash: String,
    pub size: u64,
    pub router: iroh::protocol::Router,
    pub temp_tag: TempTag,
    pub store: MemStore,
    pub _progress_handle: AbortOnDropHandle<anyhow::Result<()>>,
}

pub struct WasmReceiveResult {
    pub files: Vec<(String, Vec<u8>)>,
}
