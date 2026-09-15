# Clima Histórico

App Android de histórico climático do Brasil: coleta diária automática de 5.571
municípios via Open-Meteo, guarda tudo em SQLite local e mostra gráficos,
estatísticas e busca por cidade — offline depois de coletar.

- Pacote: `com.historicoclima`
- Dados: `https://api.open-meteo.com/v1/forecast?latitude=…` (sem chave).
- Base de cidades embutida (`CitiesData`: 5.571 códigos, nomes, UFs, lat/lon).

## Funcionalidades

- Coleta agendada em 2º plano (`CollectService` + alarmes + boot).
- Busca de cidade com autocomplete (código IBGE, nome, UF).
- Favoritos e monitoramento de cidades.
- Gráficos próprios (`GraphView`): temperatura, chuva, umidade por dia.
- Estatísticas: leituras, previsões, falhas, totais.
- Fuso padrão `America/Sao_Paulo`.

## Permissões

`INTERNET`, `ACCESS_NETWORK_STATE`, serviço em 1º plano (data-sync),
alarmes, notificações, boot. Sem localização (usa coordenadas da tabela).

## Compilar e permissões

```sh
cd ~/projects/historico-clima
bash build.sh
bash grant_permissions.sh   # opcional: libera alarmes/notificações via ADB
```

APK `Clima-Historico.apk` em `build/`. Testes em `tests/`.

## Estrutura

```
historico-clima/
├── src/com/historicoclima/
│   ├── MainActivity.java / TabController.java  # abas
│   ├── CitiesData.java      # 5.571 municípios
│   ├── WeatherFetcher.java  # cliente Open-Meteo
│   ├── DatabaseHelper.java  # SQLite
│   ├── CollectService.java / AlarmScheduler.java / AlarmReceiver.java / BootReceiver.java
│   ├── GraphView.java       # gráficos
│   └── CitySearch.java / ConfigReceiver.java
└── res/ / tests/ / tools/
```
