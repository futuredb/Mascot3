const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const path = require("node:path");
const { choose } = require("./deal.js");

async function referencePacks(root) {
  const entries = await fs.readdir(root, { withFileTypes: true });
  const directImages = entries
    .filter((entry) => entry.isFile() && /\.(png|jpe?g|webp)$/i.test(entry.name))
    .map((entry) => path.join(root, entry.name));
  const packs = directImages.length ? [{ dir: root, files: directImages }] : [];
  for (const entry of entries.filter((entry) => entry.isDirectory())) {
    packs.push(...await referencePacks(path.join(root, entry.name)));
  }
  return packs;
}

async function buildReferenceManifest(root, seed) {
  const packs = await referencePacks(root);
  if (!packs.length) throw new Error(`No image reference packs found in ${root}`);
  const pack = choose(packs, seed, "refs.style_pack");
  const files = pack.files;
  const byHash = new Map();
  for (const file of files) {
    const hash = crypto.createHash("sha256").update(await fs.readFile(file)).digest("hex");
    if (!byHash.has(hash)) byHash.set(hash, { hash, file, duplicates: [] });
    else byHash.get(hash).duplicates.push(file);
  }
  const unique = [...byHash.values()].sort((a, b) => a.hash.localeCompare(b.hash));
  if (!unique.length) throw new Error(`No unique refs found in ${pack.dir}`);
  const used = new Set();
  const pick = (index) => {
    const candidates = unique.filter((entry) => !used.has(entry.hash));
    const value = choose(candidates, seed, `refs.image.${index}`);
    used.add(value.hash);
    return { hash: value.hash, source: value.file, duplicates: value.duplicates };
  };
  return { refs_root: path.resolve(root), style_pack: path.resolve(pack.dir), seed, references: Array.from({ length: unique.length }, (_, index) => pick(index)) };
}

async function copySelectedReferences(manifest, destination) {
  await fs.mkdir(destination, { recursive: true });
  await Promise.all(manifest.references.map((reference, index) =>
    fs.copyFile(reference.source, path.join(destination, `ref_${String(index).padStart(2, "0")}${path.extname(reference.source).toLowerCase()}`))));
}
module.exports = { buildReferenceManifest, copySelectedReferences };
