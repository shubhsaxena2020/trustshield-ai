from services.api.settings import Settings


def test_settings_read_supabase_config_from_env(monkeypatch):
    monkeypatch.setenv("SUPABASE_URL", "https://example.supabase.co/")
    monkeypatch.setenv("SUPABASE_SERVICE_ROLE_KEY", " secret-key ")
    settings = Settings.from_env()
    assert settings.supabase_url == "https://example.supabase.co"
    assert settings.supabase_service_role_key == "secret-key"


def test_settings_default_supabase_config_is_empty(monkeypatch):
    monkeypatch.delenv("SUPABASE_URL", raising=False)
    monkeypatch.delenv("SUPABASE_SERVICE_ROLE_KEY", raising=False)
    settings = Settings.from_env()
    assert settings.supabase_url == ""
    assert settings.supabase_service_role_key == ""
