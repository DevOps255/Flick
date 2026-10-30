//! Browser import: in-memory bytes → iroh-blobs store.

use anyhow::{ensure, Context};
use iroh_blobs::api::blobs::AddProgressItem;
use iroh_blobs::{
    api::{Store, TempTag},
    format::collection::Collection,
};
use n0_future::StreamExt;
use std::collections::BTreeMap;

/// Import a single file from raw bytes (wrapped as a one-entry collection).
pub async fn import_single_file_bytes(
    file_name: String,
    bytes: Vec<u8>,
    db: &Store,
) -> anyhow::Result<(TempTag, u64)> {
    let mut stream = db.blobs().add_bytes(bytes).stream().await;
    let mut item_size = 0u64;
    let file_tag = loop {
        let item = stream
            .next()
            .await
            .context("import stream ended without a tag")?;
        match item {
            AddProgressItem::Size(size) => item_size = size,
            AddProgressItem::Done(tt) => break tt,
            AddProgressItem::Error(cause) => {
                anyhow::bail!("error importing bytes: {cause}")
            }
            _ => {}
        }
    };

    let collection = Collection::from_iter([(file_name, file_tag.hash())]);
    let temp_tag = collection.clone().store(db).await?;
    Ok((temp_tag, item_size))
}

fn dedup_name(name: &str, seen: &mut BTreeMap<String, usize>) -> String {
    match seen.get_mut(name) {
        Some(count) => {
            *count += 1;
            format!("{} ({})", name, count)
        }
        None => {
            seen.insert(name.to_string(), 1);
            name.to_string()
        }
    }
}

/// Import one or more named byte blobs into a single iroh-blobs collection.
pub async fn import_named_bytes_collection(
    items: Vec<(String, Vec<u8>)>,
    db: &Store,
) -> anyhow::Result<(TempTag, u64)> {
    let mut name_seen: BTreeMap<String, usize> = BTreeMap::new();
    let mut entries: Vec<(String, TempTag, u64)> = Vec::with_capacity(items.len());

    for (name, bytes) in items {
        let mut stream = db.blobs().add_bytes(bytes).stream().await;
        let mut item_size = 0u64;
        let file_tag = loop {
            let item = stream
                .next()
                .await
                .context("import stream ended without a tag")?;
            match item {
                AddProgressItem::Size(size) => item_size = size,
                AddProgressItem::Done(tt) => break tt,
                AddProgressItem::Error(cause) => {
                    anyhow::bail!("error importing {name}: {cause}")
                }
                _ => {}
            }
        };

        let final_name = dedup_name(&name, &mut name_seen);
        entries.push((final_name, file_tag, item_size));
    }

    entries.sort_by(|a, b| a.0.cmp(&b.0));
    ensure!(!entries.is_empty(), "no files to import");

    let total_size = entries.iter().map(|(_, _, size)| *size).sum::<u64>();
    let (collection, tags): (Collection, Vec<_>) = entries
        .into_iter()
        .map(|(name, tag, _)| ((name, tag.hash()), tag))
        .unzip();

    let temp_tag = collection.clone().store(db).await?;
    drop(tags);
    Ok((temp_tag, total_size))
}
