//! Key names and a US keyboard layout, translated to Linux evdev key codes.
//!
//! Both the phone (sending `{"t":"key","name":"esc"}` / `{"t":"text","s":"Hi"}`)
//! and the host (pressing keys through uinput) use this table, so clients never
//! have to know about Linux input internals.

// Key codes from <linux/input-event-codes.h>.
pub const KEY_ESC: u16 = 1;
pub const KEY_MINUS: u16 = 12;
pub const KEY_EQUAL: u16 = 13;
pub const KEY_BACKSPACE: u16 = 14;
pub const KEY_TAB: u16 = 15;
pub const KEY_LEFTBRACE: u16 = 26;
pub const KEY_RIGHTBRACE: u16 = 27;
pub const KEY_ENTER: u16 = 28;
pub const KEY_LEFTCTRL: u16 = 29;
pub const KEY_SEMICOLON: u16 = 39;
pub const KEY_APOSTROPHE: u16 = 40;
pub const KEY_GRAVE: u16 = 41;
pub const KEY_LEFTSHIFT: u16 = 42;
pub const KEY_BACKSLASH: u16 = 43;
pub const KEY_COMMA: u16 = 51;
pub const KEY_DOT: u16 = 52;
pub const KEY_SLASH: u16 = 53;
pub const KEY_RIGHTSHIFT: u16 = 54;
pub const KEY_LEFTALT: u16 = 56;
pub const KEY_SPACE: u16 = 57;
pub const KEY_CAPSLOCK: u16 = 58;
pub const KEY_F1: u16 = 59;
pub const KEY_F10: u16 = 68;
pub const KEY_F11: u16 = 87;
pub const KEY_F12: u16 = 88;
pub const KEY_RIGHTCTRL: u16 = 97;
pub const KEY_SYSRQ: u16 = 99;
pub const KEY_RIGHTALT: u16 = 100;
pub const KEY_HOME: u16 = 102;
pub const KEY_UP: u16 = 103;
pub const KEY_PAGEUP: u16 = 104;
pub const KEY_LEFT: u16 = 105;
pub const KEY_RIGHT: u16 = 106;
pub const KEY_END: u16 = 107;
pub const KEY_DOWN: u16 = 108;
pub const KEY_PAGEDOWN: u16 = 109;
pub const KEY_INSERT: u16 = 110;
pub const KEY_DELETE: u16 = 111;
pub const KEY_LEFTMETA: u16 = 125;
pub const KEY_RIGHTMETA: u16 = 126;

/// Letters are laid out by physical key position, not alphabetically:
/// Q=16 … P=25, A=30 … L=38, Z=44 … M=50.
fn letter(c: char) -> Option<u16> {
    let code = match c.to_ascii_lowercase() {
        'q' => 16,
        'w' => 17,
        'e' => 18,
        'r' => 19,
        't' => 20,
        'y' => 21,
        'u' => 22,
        'i' => 23,
        'o' => 24,
        'p' => 25,
        'a' => 30,
        's' => 31,
        'd' => 32,
        'f' => 33,
        'g' => 34,
        'h' => 35,
        'j' => 36,
        'k' => 37,
        'l' => 38,
        'z' => 44,
        'x' => 45,
        'c' => 46,
        'v' => 47,
        'b' => 48,
        'n' => 49,
        'm' => 50,
        _ => return None,
    };
    Some(code)
}

/// Digits: KEY_1 is 2, KEY_0 is 11.
fn digit(c: char) -> Option<u16> {
    match c {
        '1'..='9' => Some(2 + (c as u16 - '1' as u16)),
        '0' => Some(11),
        _ => None,
    }
}

/// A key name that is a single printable character, e.g. `"s"` or `"S"`.
///
/// Lets a client send `{"t":"key","name":"c","mods":["ctrl"]}` instead of
/// going through the text path, which is what soft keyboards use for combos.
pub fn by_char(name: &str) -> Option<(u16, bool)> {
    let mut chars = name.chars();
    let c = chars.next()?;
    if chars.next().is_some() {
        return None;
    }
    for_char(c)
}

/// Named keys, as typed by the phone's toolbar.
pub fn by_name(name: &str) -> Option<u16> {
    let name = name.trim().to_ascii_lowercase();
    let code = match name.as_str() {
        "esc" | "escape" => KEY_ESC,
        "tab" => KEY_TAB,
        "enter" | "return" => KEY_ENTER,
        "backspace" | "bksp" => KEY_BACKSPACE,
        "space" => KEY_SPACE,
        "delete" | "del" => KEY_DELETE,
        "insert" | "ins" => KEY_INSERT,
        "home" => KEY_HOME,
        "end" => KEY_END,
        "pageup" | "pgup" => KEY_PAGEUP,
        "pagedown" | "pgdn" => KEY_PAGEDOWN,
        "up" => KEY_UP,
        "down" => KEY_DOWN,
        "left" => KEY_LEFT,
        "right" => KEY_RIGHT,
        "capslock" | "caps" => KEY_CAPSLOCK,
        "printscreen" | "prtsc" => KEY_SYSRQ,
        "minus" => KEY_MINUS,
        "equal" => KEY_EQUAL,
        "comma" => KEY_COMMA,
        "dot" | "period" => KEY_DOT,
        "slash" => KEY_SLASH,
        "semicolon" => KEY_SEMICOLON,
        "apostrophe" | "quote" => KEY_APOSTROPHE,
        "grave" | "backtick" => KEY_GRAVE,
        "backslash" => KEY_BACKSLASH,
        "leftbrace" | "bracketleft" => KEY_LEFTBRACE,
        "rightbrace" | "bracketright" => KEY_RIGHTBRACE,
        "f1" => KEY_F1,
        "f2" => KEY_F1 + 1,
        "f3" => KEY_F1 + 2,
        "f4" => KEY_F1 + 3,
        "f5" => KEY_F1 + 4,
        "f6" => KEY_F1 + 5,
        "f7" => KEY_F1 + 6,
        "f8" => KEY_F1 + 7,
        "f9" => KEY_F1 + 8,
        "f10" => KEY_F10,
        "f11" => KEY_F11,
        "f12" => KEY_F12,
        _ => return None,
    };
    Some(code)
}

/// Modifier names accepted in `mods`.
pub fn modifier(name: &str) -> Option<u16> {
    match name.trim().to_ascii_lowercase().as_str() {
        "ctrl" | "control" | "lctrl" => Some(KEY_LEFTCTRL),
        "alt" | "meta_alt" => Some(KEY_LEFTALT),
        "shift" => Some(KEY_LEFTSHIFT),
        "super" | "meta" | "win" | "logo" => Some(KEY_LEFTMETA),
        "rmeta" | "rsuper" => Some(KEY_RIGHTMETA),
        "rctrl" => Some(KEY_RIGHTCTRL),
        "ralt" | "altgr" => Some(KEY_RIGHTALT),
        "rshift" => Some(KEY_RIGHTSHIFT),
        _ => None,
    }
}

/// US layout: character -> (key code, needs shift).
pub fn for_char(c: char) -> Option<(u16, bool)> {
    if let Some(code) = letter(c) {
        return Some((code, c.is_ascii_uppercase()));
    }
    if let Some(code) = digit(c) {
        return Some((code, false));
    }

    let simple = match c {
        ' ' => (KEY_SPACE, false),
        '\n' | '\r' => (KEY_ENTER, false),
        '\t' => (KEY_TAB, false),
        '-' => (KEY_MINUS, false),
        '_' => (KEY_MINUS, true),
        '=' => (KEY_EQUAL, false),
        '+' => (KEY_EQUAL, true),
        '[' => (KEY_LEFTBRACE, false),
        '{' => (KEY_LEFTBRACE, true),
        ']' => (KEY_RIGHTBRACE, false),
        '}' => (KEY_RIGHTBRACE, true),
        '\\' => (KEY_BACKSLASH, false),
        '|' => (KEY_BACKSLASH, true),
        ';' => (KEY_SEMICOLON, false),
        ':' => (KEY_SEMICOLON, true),
        '\'' => (KEY_APOSTROPHE, false),
        '"' => (KEY_APOSTROPHE, true),
        '`' => (KEY_GRAVE, false),
        '~' => (KEY_GRAVE, true),
        ',' => (KEY_COMMA, false),
        '<' => (KEY_COMMA, true),
        '.' => (KEY_DOT, false),
        '>' => (KEY_DOT, true),
        '/' => (KEY_SLASH, false),
        '?' => (KEY_SLASH, true),
        '!' => (digit('1')?, true),
        '@' => (digit('2')?, true),
        '#' => (digit('3')?, true),
        '$' => (digit('4')?, true),
        '%' => (digit('5')?, true),
        '^' => (digit('6')?, true),
        '&' => (digit('7')?, true),
        '*' => (digit('8')?, true),
        '(' => (digit('9')?, true),
        ')' => (digit('0')?, true),
        _ => return None,
    };
    Some(simple)
}

/// Every key code the host may ever press, so uinput can advertise them.
pub fn all_codes() -> Vec<u16> {
    let mut codes: Vec<u16> = (b'a'..=b'z').map(|c| letter(c as char).unwrap()).collect();
    codes.extend((b'0'..=b'9').map(|c| digit(c as char).unwrap()));
    codes.extend((KEY_F1..=KEY_F1 + 9).chain(std::iter::once(KEY_F10)));
    codes.extend([KEY_F11, KEY_F12]);

    let named = [
        "esc", "tab", "enter", "backspace", "space", "delete", "insert", "home", "end",
        "pageup", "pagedown", "up", "down", "left", "right", "capslock", "printscreen",
        "minus", "equal", "comma", "dot", "slash", "semicolon", "apostrophe", "grave",
        "backslash", "leftbrace", "rightbrace",
    ];
    codes.extend(named.iter().filter_map(|n| by_name(n)));
    codes.extend(
        ["ctrl", "alt", "shift", "super", "rctrl", "ralt", "rshift"]
            .iter()
            .filter_map(|n| modifier(n)),
    );
    codes.sort_unstable();
    codes.dedup();
    codes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn letters_and_digits() {
        assert_eq!(for_char('a'), Some((30, false)));
        assert_eq!(for_char('A'), Some((30, true)));
        assert_eq!(for_char('q'), Some((16, false)));
        assert_eq!(for_char('b'), Some((48, false)));
        assert_eq!(for_char('z'), Some((44, false)));
        assert_eq!(for_char('m'), Some((50, false)));
        assert_eq!(for_char('1'), Some((2, false)));
        assert_eq!(for_char('0'), Some((11, false)));
    }

    #[test]
    fn shifted_symbols() {
        assert_eq!(for_char('!'), Some((2, true)));
        assert_eq!(for_char('?'), Some((53, true)));
        assert_eq!(for_char('_'), Some((12, true)));
    }

    #[test]
    fn names() {
        assert_eq!(by_name("ESC"), Some(1));
        assert_eq!(by_name("f12"), Some(88));
        assert_eq!(modifier("Super"), Some(125));
        assert_eq!(by_name("nope"), None);
        assert_eq!(by_char("s"), Some((31, false)));
        assert_eq!(by_char("S"), Some((31, true)));
        assert_eq!(by_char("7"), Some((8, false)));
        assert_eq!(by_char("?"), Some((53, true)));
        assert_eq!(by_char("ab"), None);
        assert_eq!(by_char(""), None);
        assert_eq!(by_char("é"), None);
    }
}
