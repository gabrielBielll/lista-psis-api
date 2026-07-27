# Sincronização central com Google Agenda

O sistema usa **uma única conta Google da Deep**. Ela precisa ter acesso de
leitura aos detalhes de cada agenda de psicóloga; acesso de escrita também
funciona e é o caso atual da clínica.

## Configuração inicial

1. No Google Cloud, crie um cliente OAuth do tipo **Aplicativo da Web** e
   habilite a Google Calendar API.
2. Cadastre exatamente a URL definida em `GOOGLE_REDIRECT_URI` como URI de
   redirecionamento autorizada.
3. Preencha as variáveis de ambiente mostradas em `.env.example`. O valor de
   `GOOGLE_TOKEN_ENCRYPTION_KEY` deve ter 32 bytes codificados em Base64.
4. Faça login como administrador no gestor e acesse **Conectar Google**.
5. Escolha, no painel de administração, qual agenda Google pertence a cada
   `psicologa_id` já existente.

## Regra de disponibilidade

Um horário é publicado somente quando o evento atende **aos dois critérios**
da convenção da Deep:

- título `[DISPONÍVEL]` (o sistema também aceita a versão sem acento);
- cor de evento Pavão ou Azul, que na API do Google correspondem aos IDs `7`
  e `9`.

Os IDs aceitos podem ser alterados por `GOOGLE_AVAILABLE_EVENT_COLOR_IDS`.
Qualquer outro evento sobreposto bloqueia a publicação do horário — incluindo
sessões com paciente, `[INDISPONÍVEL]`, férias e bloqueios pessoais. Assim,
nenhum título ou dado de paciente é exposto pelo endpoint público.

## Convivência com a agenda manual

A grade semanal manual já existente continua publicada para psicólogas que
ainda não têm agenda Google vinculada. Ao vincular uma agenda Google, os
eventos `[DISPONÍVEL]` passam a ser a fonte principal daquela psicóloga para
evitar conflitos com uma grade semanal que tenha ficado desatualizada.

Uma exceção manual salva no gestor tem precedência sobre a importação:

- `disponivel: false` bloqueia um horário importado;
- `disponivel: true` abre um horário pontual extra.

O endpoint público nunca devolve título, paciente, descrição ou convidados de
eventos Google.
