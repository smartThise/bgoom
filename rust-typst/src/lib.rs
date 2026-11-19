use std::fs;
use std::path::Path;
use std::sync::OnceLock;

use jni::objects::{JClass, JString};
use jni::sys::jbyteArray;
use jni::JNIEnv;
use typst::diag::{FileError, FileResult};
use typst::foundations::{Bytes, Datetime};
use typst::layout::PagedDocument;
use typst::syntax::{FileId, Source, VirtualPath};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, LibraryExt, World};
use typst_pdf::PdfOptions;

struct SharedFonts {
    book: LazyHash<FontBook>,
    fonts: Vec<Font>,
}

static SHARED_FONTS: OnceLock<SharedFonts> = OnceLock::new();
static SHARED_LIBRARY: OnceLock<LazyHash<Library>> = OnceLock::new();

fn should_load_system_font(filename_lower: &str) -> bool {
    let ext_ok = filename_lower.ends_with(".ttf")
        || filename_lower.ends_with(".otf")
        || filename_lower.ends_with(".ttc");
    if !ext_ok {
        return false;
    }
    let keywords = [
        "cjk",
        "sc",
        "hans",
        "song",
        "hei",
        "kai",
        "fang",
        "pingfang",
        "hiragino",
        "sourcehan",
        "notosans",
        "notoserif",
        "roboto",
        "droidsans",
        "honor",
        "harmony",
        "miui",
        "hw",
    ];
    // Exclude huge color emoji fonts or unrelated scripts
    if filename_lower.contains("emoji")
        || filename_lower.contains("arabic")
        || filename_lower.contains("hebrew")
        || filename_lower.contains("thai")
        || filename_lower.contains("devanagari")
        || filename_lower.contains("bengali")
        || filename_lower.contains("tamil")
        || filename_lower.contains("telugu")
        || filename_lower.contains("kannada")
        || filename_lower.contains("malayalam")
        || filename_lower.contains("sinhala")
        || filename_lower.contains("myanmar")
        || filename_lower.contains("khmer")
        || filename_lower.contains("lao")
        || filename_lower.contains("tibetan")
        || filename_lower.contains("georgian")
        || filename_lower.contains("armenian")
        || filename_lower.contains("ethiopic")
        || filename_lower.contains("gurmukhi")
        || filename_lower.contains("gujarati")
        || filename_lower.contains("oriya")
    {
        return false;
    }
    keywords.iter().any(|k| filename_lower.contains(k))
}

fn load_fonts_from_dir(dir: &Path, book: &mut FontBook, fonts: &mut Vec<Font>, max_files: usize) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    let mut loaded = 0usize;
    for entry in entries.flatten() {
        if loaded >= max_files {
            break;
        }
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        let lower = name.to_ascii_lowercase();
        if !should_load_system_font(&lower) {
            continue;
        }
        if let Ok(data) = fs::read(&path) {
            let bytes = Bytes::new(data);
            let mut added = false;
            for font in Font::iter(bytes) {
                book.push(font.info().clone());
                fonts.push(font);
                added = true;
            }
            if added {
                loaded += 1;
            }
        }
    }
}

fn get_shared_fonts(extra_dirs: &[String]) -> &'static SharedFonts {
    SHARED_FONTS.get_or_init(|| {
        let mut book = FontBook::new();
        let mut fonts = Vec::new();

        // 1. Embedded typst-assets fonts (Libertinus Serif, New Computer Modern Math, DejaVu Sans Mono)
        for data in typst_assets::fonts() {
            let bytes = Bytes::new(data);
            for font in Font::iter(bytes) {
                book.push(font.info().clone());
                fonts.push(font);
            }
        }

        // 2. Android & desktop system CJK/Sans fonts
        let default_dirs = [
            "/system/fonts",
            "/product/fonts",
            "/system_ext/fonts",
            "/vendor/fonts",
            "/System/Library/Fonts",
            "/System/Library/Fonts/Supplemental",
        ];
        for dir in default_dirs {
            load_fonts_from_dir(Path::new(dir), &mut book, &mut fonts, 24);
        }
        for dir in extra_dirs {
            if !dir.trim().is_empty() {
                load_fonts_from_dir(Path::new(dir.trim()), &mut book, &mut fonts, 16);
            }
        }

        SharedFonts {
            book: LazyHash::new(book),
            fonts,
        }
    })
}

fn get_shared_library() -> &'static LazyHash<Library> {
    SHARED_LIBRARY.get_or_init(|| LazyHash::new(Library::default()))
}

struct BgoomWorld {
    main_id: FileId,
    source: Source,
    fonts: &'static SharedFonts,
    library: &'static LazyHash<Library>,
}

impl BgoomWorld {
    fn new(text: String, extra_dirs: &[String]) -> Self {
        let main_id = FileId::new(None, VirtualPath::new("main.typ"));
        let source = Source::new(main_id, text);
        Self {
            main_id,
            source,
            fonts: get_shared_fonts(extra_dirs),
            library: get_shared_library(),
        }
    }
}

impl World for BgoomWorld {
    fn library(&self) -> &LazyHash<Library> {
        self.library
    }

    fn book(&self) -> &LazyHash<FontBook> {
        &self.fonts.book
    }

    fn main(&self) -> FileId {
        self.main_id
    }

    fn source(&self, id: FileId) -> FileResult<Source> {
        if id == self.main_id {
            Ok(self.source.clone())
        } else {
            Err(FileError::NotFound(id.vpath().as_rootless_path().into()))
        }
    }

    fn file(&self, id: FileId) -> FileResult<Bytes> {
        Err(FileError::NotFound(id.vpath().as_rootless_path().into()))
    }

    fn font(&self, index: usize) -> Option<Font> {
        self.fonts.fonts.get(index).cloned()
    }

    fn today(&self, _offset: Option<i64>) -> Option<Datetime> {
        Datetime::from_ymd(2026, 11, 19)
    }
}

pub fn compile_typst_to_pdf(source: &str, extra_dirs: &[String]) -> Result<Vec<u8>, String> {
    let world = BgoomWorld::new(source.to_string(), extra_dirs);
    let warned = typst::compile::<PagedDocument>(&world);
    let document = warned.output.map_err(|errors| {
        errors
            .iter()
            .map(|e| e.message.to_string())
            .collect::<Vec<_>>()
            .join("\n")
    })?;

    typst_pdf::pdf(&document, &PdfOptions::default()).map_err(|errors| {
        errors
            .iter()
            .map(|e| e.message.to_string())
            .collect::<Vec<_>>()
            .join("\n")
    })
}

pub fn list_loaded_font_families(extra_dirs: &[String]) -> Vec<String> {
    let shared = get_shared_fonts(extra_dirs);
    let mut names: Vec<String> = shared
        .fonts
        .iter()
        .map(|f| f.info().family.clone())
        .collect();
    names.sort();
    names.dedup();
    names
}

/// Deterministic AST-level LaTeX -> Typst conversion powered by Tylax (mitex-parser + typst-syntax),
/// plus clean post-processing (strip redundant \left/\right, normalize differentials & delimiters).
pub fn convert_latex_to_typst(latex: &str, clean_style: bool) -> String {
    let trimmed = latex.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    let preprocessed = if clean_style {
        trimmed
            .replace(r"\left(", "(")
            .replace(r"\right)", ")")
            .replace(r"\left[", "[")
            .replace(r"\right]", "]")
            .replace(r"\mathrm{d}", r"\operatorname{dif}")
            .replace(r"\mathrm{ d }", r"\operatorname{dif}")
            .replace(r"\text{d}", r"\operatorname{dif}")
    } else {
        trimmed.to_string()
    };

    // Detect whether caller passed a standalone math formula or a full document
    let looks_like_full_doc = preprocessed.contains(r"\begin{document}")
        || preprocessed.contains(r"\section")
        || preprocessed.contains("$$")
        || (preprocessed.contains('$') && preprocessed.matches('$').count() >= 2);

    let raw_typst = if looks_like_full_doc {
        tylax::latex_document_to_typst(&preprocessed)
    } else {
        let wrapped = format!("$${preprocessed}$$");
        let conv = tylax::latex_to_typst(&wrapped);
        conv
    };

    if clean_style {
        raw_typst
            .replace("op(\"dif\")", "dif")
            .replace("upright(d)", "dif")
            .trim()
            .to_string()
    } else {
        raw_typst.trim().to_string()
    }
}

pub fn convert_typst_to_latex(typst_src: &str) -> String {
    let trimmed = typst_src.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    tylax::typst_to_latex(trimmed).trim().to_string()
}

#[no_mangle]
pub extern "system" fn Java_com_bgoom_app_typst_NativeTypstEngine_nativeLatexToTypst(
    mut env: JNIEnv,
    _class: JClass,
    latex: JString,
    clean_style: jni::sys::jboolean,
) -> jni::sys::jstring {
    let input: String = match env.get_string(&latex) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let converted = convert_latex_to_typst(&input, clean_style != 0);
    match env.new_string(converted) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bgoom_app_typst_NativeTypstEngine_nativeTypstToLatex(
    mut env: JNIEnv,
    _class: JClass,
    typst_src: JString,
) -> jni::sys::jstring {
    let input: String = match env.get_string(&typst_src) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let converted = convert_typst_to_latex(&input);
    match env.new_string(converted) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bgoom_app_typst_NativeTypstEngine_nativeCompileToPdf(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    extra_font_dirs: JString,
) -> jbyteArray {
    let src_str: String = match env.get_string(&source) {
        Ok(s) => s.into(),
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", format!("Invalid source: {e}"));
            return std::ptr::null_mut();
        }
    };
    let extra_str: String = match env.get_string(&extra_font_dirs) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let dirs: Vec<String> = extra_str
        .split(':')
        .filter(|s| !s.trim().is_empty())
        .map(|s| s.to_string())
        .collect();

    match compile_typst_to_pdf(&src_str, &dirs) {
        Ok(pdf_bytes) => match env.byte_array_from_slice(&pdf_bytes) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                let _ = env.throw_new("java/lang/RuntimeException", format!("JNI byte array error: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(msg) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", msg);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bgoom_app_typst_NativeTypstEngine_nativeLoadedFonts(
    mut env: JNIEnv,
    _class: JClass,
    extra_font_dirs: JString,
) -> jni::sys::jstring {
    let extra_str: String = env
        .get_string(&extra_font_dirs)
        .map(|s| s.into())
        .unwrap_or_default();
    let dirs: Vec<String> = extra_str
        .split(':')
        .filter(|s| !s.trim().is_empty())
        .map(|s| s.to_string())
        .collect();
    let joined = list_loaded_font_families(&dirs).join("\n");
    match env.new_string(joined) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compiles_cjk_and_math_to_valid_pdf() {
        let src = r#"
#set page(paper: "a4", margin: 2cm)
#set text(font: ("Noto Sans CJK SC", "PingFang SC", "Libertinus Serif"), size: 11pt)

= 大爆 BGOOM Typst 离线编译测试

这是一个支持中文与数学公式的本地编译引擎：

$ integral_0^infinity e^(-x^2) dif x = sqrt(pi) / 2 $
"#;
        let pdf = compile_typst_to_pdf(src, &[]).expect("Typst compile must succeed");
        assert!(pdf.starts_with(b"%PDF-"), "Output must start with %PDF-");
        assert!(pdf.len() > 2000, "PDF should contain rendered glyph streams");
    }

    #[test]
    fn returns_error_on_invalid_typst() {
        let res = compile_typst_to_pdf("#unknown_func_xyz()", &[]);
        assert!(res.is_err());
    }

    #[test]
    fn converts_latex_formula_via_tylax_ast_and_compiles_pdf() {
        let latex = r"\int_{0}^{\infty} e^{-x^2} \mathrm{d}x = \frac{\sqrt{\pi}}{2}";
        let typst_math = convert_latex_to_typst(latex, true);
        println!("Tylax converted: {typst_math}");
        assert!(typst_math.contains("integral") || typst_math.contains("int"));
        let doc = format!("#set page(paper: \"a4\")\n{typst_math}");
        let pdf = compile_typst_to_pdf(&doc, &[]).expect("Converted Typst must compile to PDF");
        assert!(pdf.starts_with(b"%PDF-"));
    }
}
