# Визуальный аудит MJReader 0.9.4

Цель — быстро найти книгу, продолжить чтение и различать действия мышью, касанием и клавиатурой. Снято на нативной сборке `609bf893e9542e0efb3073911b8fd3d0d5f15c3e` на эмуляторе Android 16, 430 × 932 dp, в тёмной и светлой темах. Для каталога использован локальный OPDS-стенд, для чтения — встроенные рассказы.

| Шаг | Состояние | Снимки Android |
| --- | --- | --- |
| 1. Холодный запуск | Графит, лавандово-розовая сборка книги, затем готовый интерфейс. При отключённом движении сразу открылась библиотека. | [Тёмная](images/visual-refinements/audit/before/01-start-dark.png) · [Светлая](images/visual-refinements/audit/before/01-start-light.png) · [Без движения](images/visual-refinements/audit/before/01-start-reduced.png) |
| 2. Библиотека | Продолжение чтения и прогресс заметны. Панель читается отдельно от карточек. У кнопки добавления различим фокус. | [Тёмная](images/visual-refinements/audit/before/02-library-dark.png) · [Светлая](images/visual-refinements/audit/before/02-library-light.png) · [Фокус](images/visual-refinements/audit/before/02-add-focus-light.png) |
| 3. Поиск и каталог | Клавиатура освобождает место, поле поиска сохраняет контур фокуса. В светлой теме выбор «По названию» требует более явного выделения. | [Тёмная](images/visual-refinements/audit/before/03-search-dark.png) · [Светлая](images/visual-refinements/audit/before/03-search-light.png) · [Результаты, тёмная](images/visual-refinements/audit/before/03-results-dark.png) · [Результаты, светлая](images/visual-refinements/audit/before/03-results-light.png) · [Клавиатура, тёмная](images/visual-refinements/audit/before/03-ime-dark.png) · [Клавиатура, светлая](images/visual-refinements/audit/before/03-ime-light.png) |
| 4. Загрузки | Пустое состояние даёт один понятный путь к каталогу. В заполненном списке разделены чтение и удаление. Значки в широких кнопках оторваны от подписей. | [Тёмная](images/visual-refinements/audit/before/04-downloads-dark.png) · [Светлая](images/visual-refinements/audit/before/04-downloads-light.png) · [Заполненный список](images/visual-refinements/audit/before/04-downloads-complete.png) |
| 5. Настройки | Группы и текущие значения читаются. Выбор шрифта уже отмечает активный пункт галочкой. | [Тёмная](images/visual-refinements/audit/before/05-settings-dark.png) · [Светлая](images/visual-refinements/audit/before/05-settings-light.png) · [Шрифт, тёмная](images/visual-refinements/audit/before/08-typeface-dark.png) · [Шрифт, светлая](images/visual-refinements/audit/before/08-typeface-light.png) |
| 6. Чтение | Текст остаётся на нейтральной поверхности. Четыре иконки панели различаются. У отдельной строки прогресса обнаружен провал контраста при наведении. | [Тёмная](images/visual-refinements/audit/before/06-reading-dark.png) · [Светлая](images/visual-refinements/audit/before/06-reading-light.png) · [Наведение на прогресс](images/visual-refinements/audit/before/06-progress-hover-light.png) |
| 7. Меню чтения | Действия подписаны. RSVP отличается от остальных строк отсутствием значка, хотя в самой панели он уже есть. | [Тёмная](images/visual-refinements/audit/before/07-reading-menu-dark.png) · [Светлая](images/visual-refinements/audit/before/07-reading-menu-light.png) |
| 8. Диалоги | Закрытие и основное действие заметны. В меню фильтра текущий выбор не виден. | [Фильтр, тёмная](images/visual-refinements/audit/before/08-library-filter-dark.png) · [Фильтр, светлая](images/visual-refinements/audit/before/08-library-filter-light.png) · [Книга, тёмная](images/visual-refinements/audit/before/08-book-details-dark.png) · [Книга, светлая](images/visual-refinements/audit/before/08-book-details-light.png) |

## Приоритеты для разработки

| Приоритет | Экран и доказательство | Правка |
| --- | --- | --- |
| Высокий | Чтение, шаг 6. На светлой теме чёрное наведение снижает контраст счётчика до 2,61:1. | Использовать тональную подсветку только в границах счётчика. Более плотную заливку оставить нажатию, контур — клавиатурному фокусу. |
| Средний | Поиск, шаг 3. Светлые заливки режимов близки, а текст одинакового цвета. | Дать выбранному режиму цвет подписи и тонкий контур. Фокус остаётся более выраженным. |
| Средний | Каталог, загрузки и детали книги, шаги 3, 4 и 8. Значок и подпись одной кнопки визуально разнесены. | Сгруппировать их у начала кнопки. Сохранить текст действий, включая удаление и загрузку. |
| Средний | Фильтр библиотеки, шаг 8. Текущий пункт можно определить только по затемнённой библиотеке за диалогом. | Отметить текущий пункт цветом, галочкой и состоянием `selected`. |
| Низкий | Меню чтения, шаг 7. RSVP остался единственной строкой без иконки. | Повторить значок скорости с панели чтения и сохранить поясняющую подпись. |

В захваченных состояниях и актуальных ресурсах приложения старого фирменного лайма нет. Красный удаления и пользовательские акценты сохранены. Проверка охватывает видимые состояния, клавиатурный фокус и эмуляторный reduced motion. Физический экран, TalkBack и живой OPDS-зеркальный сервер в аудит не входили.

## До и после

| Правка | До | После |
| --- | --- | --- |
| Наведение на прогресс | [Светлая тема](images/visual-refinements/audit/before/06-progress-hover-light.png) | [Наведение](images/visual-refinements/audit/after/06-progress-hover-light.png) · [Фокус](images/visual-refinements/audit/after/06-progress-focus-light.png) · [Нажатие](images/visual-refinements/audit/after/06-progress-pressed-light.png) |
| Выбранный режим поиска | [Светлая тема](images/visual-refinements/audit/before/03-search-light.png) | [Светлая](images/visual-refinements/audit/after/03-search-light.png) · [Тёмная](images/visual-refinements/audit/after/03-search-dark.png) |
| Иконки рядом с подписями | [Каталог](images/visual-refinements/audit/before/03-results-light.png) · [Загрузки](images/visual-refinements/audit/before/04-downloads-complete.png) | [Каталог](images/visual-refinements/audit/after/03-results-light.png) · [Загрузки](images/visual-refinements/audit/after/04-downloads-complete.png) · [Детали](images/visual-refinements/audit/after/08-book-details-light.png) |
| Отметка фильтра | [Светлая тема](images/visual-refinements/audit/before/08-library-filter-light.png) | [Светлая](images/visual-refinements/audit/after/08-library-filter-light.png) · [Тёмная](images/visual-refinements/audit/after/08-library-filter-dark.png) |
| Значок RSVP | [Тёмная тема](images/visual-refinements/audit/before/07-reading-menu-dark.png) | [Тёмная](images/visual-refinements/audit/after/07-reading-menu-dark.png) · [Светлая](images/visual-refinements/audit/after/07-reading-menu-light.png) |

Предыдущие правки пакета проверены отдельно на снимках [нового фона нижней панели](images/visual-refinements/navigation) и [состояний кнопок до и после](images/visual-refinements/reader-panel).
