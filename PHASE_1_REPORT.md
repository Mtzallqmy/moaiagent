# AgentDroid — تقرير تشديد بنية المرحلة الأولى

## نطاق التنفيذ

نُفذت تعديلات البرومبت على بنية مشروع Android الحالية مع الالتزام بطلب تأجيل تحويل المشروع إلى APK. لذلك لم يتم تشغيل `assembleDebug` أو `assembleRelease` في هذه الدورة، ولم تُستبدل أولوية البنية باختبار artifact نهائي.

## البنية المنفذة

أصبح المشروع مقسمًا بوضوح إلى وحدات `app` و`core:model` و`core:ai` و`data:database`. أضيف داخل التطبيق `AgentDroidApplication` و`AppContainer` ليكونا بديلًا معماريًا واضحًا عن ربط Room والاعتمادات مباشرة بالـ Activity. كما أضيفت طبقات `Repositories` و`UseCases` وViewModels مستقلة للمحادثة والمزودات والمحادثات ومساحات العمل والذاكرة والمهارات والإعدادات.

المسار المعتمد الآن هو:

> Compose UI → ViewModels → Use Cases/Repositories → Room أو ProviderRegistry → مزود AI مستقل → OkHttp Transport.

تستخدم طبقة النقل `suspendCancellableCoroutine`، وتُلغي اتصال OkHttp عند إلغاء coroutine، وتفصل أخطاء المصادقة ومحدودية المعدل والمهلة والشبكة وSSL وأخطاء المزود والتسلسل. لا تعتمد الواجهة على SDK خاص بمزود واحد.

## مزودو الذكاء الاصطناعي

تم فصل تطبيقات OpenAI وOpenRouter وOpenAI-Compatible داخل طبقة توافق مشتركة مع بقاء كل مزود قابلًا للضبط بشكل مستقل. لكل إعداد مزود UUID مستقل، واسم، ونوع، وBase URL، ونموذج، وOrganization ID، وApp Name، وSite URL، وترويسات مخصصة. لا يوجد ربط بين هوية سجل الإعداد وبين اسم enum الخاص بالمزود.

تم تنفيذ Anthropic باستخدام Messages API الأصلي على `/messages` مع `x-api-key` و`anthropic-version` وحقول `system` و`max_tokens` و`stream`. يدعم parser أحداث `message_start` و`content_block_delta` و`message_delta` و`message_stop`، ويستخرج النص والتفكير والاستخدام ويتجاهل الأحداث غير المعروفة بأمان، وفق تسلسل SSE الموثق رسميًا.[1]

تم تنفيذ Gemini باستخدام REST الأصلي على `models/{model}:streamGenerateContent` مع `contents[].parts[]` و`systemInstruction` و`generationConfig` و`usageMetadata`، مع فصل قائمة النماذج على `/models`. هذه هي endpoints الرسمية الموثقة لـ `generateContent` و`streamGenerateContent`.[2]

## التخزين والأمان

تم الإبقاء على Room للمحادثات والرسائل وإعدادات المزودين ومساحات العمل والذاكرة والمهارات وإعدادات التطبيق. أضيفت حدود Repository للحذف والأرشفة والبحث والتفعيل وتحديد النموذج، مع نقطة `DatabaseMigrations.ALL` قابلة للتوسع دون السماح بـ destructive migration.

تُحفظ مفاتيح API عبر Android Keystore باستخدام AES/GCM مع IV جديد لكل عملية تشفير، بينما يبقى في سجل المزود alias فقط. أضيفت عمليات `contains` و`mask` و`clear` وreplacement آمن. كما تم تعطيل النسخ الاحتياطي في manifest. أضيفت DataStore Preferences لإعداد اللغة والثيم والألوان الديناميكية والمزود والنموذج الافتراضيين ووضع المطور.

## الواجهات وتجربة الاستخدام

أصبح `MainActivity` نقطة إطلاق صغيرة، بينما انتقلت composition إلى `AgentDroidRoot`. تشمل الواجهات Home وChat وProviders وModels وConversations وWorkspaces وMemory وSkills وSettings وAdd Provider. أضيفت selectors للمزود والنموذج، واختبار اتصال يعرض النتيجة والـ latency وعدد النماذج، وتفعيل/تعطيل المزود، وحذف مع تأكيد، وتدفقات CRUD أساسية لمساحات العمل والذاكرة والمهارات.

يدعم Chat حفظ الرسالة الجزئية أثناء البث، حالات `SUBMITTING` و`STREAMING` و`COMPLETED` و`FAILED` و`CANCELLED`، الإيقاف عبر إلغاء الاتصال، إعادة المحاولة دون إضافة رسالة مستخدم ثانية، وإعادة التوليد. يستخدم عرض الرسائل CommonMark فعليًا مع دعم GFM tables وcode blocks وselection/copy بدل عرض Markdown كنص خام فقط. أضيفت موارد عربية وإنجليزية ودعم RTL وتغيير اللغة عبر DataStore.

## الاختبارات والفحوصات

أضيفت اختبارات MockWebServer لبروتوكولات OpenAI SSE وAnthropic Messages SSE وGemini streaming JSON، وللأخطاء 401، مع اختبار registry. أضيف اختبار Compose smoke test للتحقق من ظهور shell الرئيسي، وقد تم تجميع مصدر اختبار Android دون تشغيله على جهاز.

تم تشغيل التحقق التالي دون إنشاء APK:

| الفحص | النتيجة |
|---|---|
| `:core:ai:testDebugUnitTest` | ناجح |
| `:app:testDebugUnitTest` | لا توجد اختبارات JVM إضافية، اكتمل بنجاح |
| `:app:compileDebugAndroidTestKotlin` | ناجح |
| `:app:compileDebugKotlin` | ناجح مع تحذيرات deprecation غير حاجزة |
| `:app:lintDebug` | ناجح |
| `git diff --check` | ناجح |
| فحص literals شبيهة بمفاتيح API أو GitHub tokens | لم يُعثر على أسرار داخل المشروع |

## ما تم تأجيله عمدًا

لم يتم تحويل المشروع إلى APK في هذه الدورة تنفيذًا لتوجيه المستخدم. كما لم يتم تشغيل اختبار UI على Emulator أو جهاز Android، ولم يتم تنفيذ طلبات API حقيقية لغياب مفاتيح فعلية ونقاط اختبار معتمدة. هذه القيود لا تعني فشل البنية، لكنها تمنع وصف المشروع بأنه Production-ready نهائيًا قبل مرحلة الاختبار الواقعي والتغليف.

## الحالة الحالية

البنية الحالية جاهزة لمواصلة العمل عليها داخل المستودع، مع تأجيل artifact النهائي إلى المرحلة التي يطلب فيها المستخدم إنشاء APK. لا ينبغي اعتبار `build/` أو APK قديم من الدورة السابقة دليلًا على أن نسخة التعديلات الحالية تم تغليفها.

## المراجع

[1]: https://platform.claude.com/docs/en/build-with-claude/streaming "Anthropic Claude Platform Docs — Streaming messages"
[2]: https://ai.google.dev/api/generate-content "Google AI for Developers — Generating content API reference"
